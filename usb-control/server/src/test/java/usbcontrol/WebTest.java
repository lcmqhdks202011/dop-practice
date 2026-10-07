package usbcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import usbcontrol.domain.AllowedDevice;
import usbcontrol.domain.Pc;
import usbcontrol.domain.UsbEvent;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebTest extends TestSupport {

    @Test
    void 로그인_안하면_로그인_화면으로() throws Exception {
        createAdmin("admin", "Passw0rd!");
        mvc.perform(get("/devices")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void 관리자가_없으면_첫_설정_화면이_나오고_한번만_만들수_있다() throws Exception {
        mvc.perform(get("/login")).andExpect(redirectedUrl("/setup"));

        mvc.perform(post("/setup").with(csrf())
                        .param("username", "admin").param("password", "short").param("passwordConfirm", "short"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        assertThat(admins.count()).isZero();

        mvc.perform(post("/setup").with(csrf())
                        .param("username", "admin").param("password", "Passw0rd!").param("passwordConfirm", "Passw0rd!"))
                .andExpect(redirectedUrl("/login?created"));
        assertThat(admins.count()).isEqualTo(1);

        // 이미 관리자가 있으면 다시 만들 수 없음
        mvc.perform(post("/setup").with(csrf())
                        .param("username", "hacker").param("password", "Passw0rd!").param("passwordConfirm", "Passw0rd!"))
                .andExpect(redirectedUrl("/login"));
        assertThat(admins.findByUsername("hacker")).isEmpty();
    }

    @Test
    void 로그인_성공과_실패가_기록되고_5번_틀리면_잠긴다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        mvc.perform(formLogin().user("admin").password("Passw0rd!")).andExpect(authenticated());
        assertThat(auditLogs.findAll()).anyMatch(l -> l.getAction().equals("로그인"));

        for (int i = 0; i < 5; i++) {
            mvc.perform(formLogin().user("admin").password("wrong")).andExpect(unauthenticated());
        }
        // 잠긴 뒤에는 맞는 비밀번호로도 못 들어감
        mvc.perform(formLogin().user("admin").password("Passw0rd!"))
                .andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error=locked"));
        assertThat(auditLogs.findAll()).anyMatch(l -> l.getAction().equals("계정 잠김"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 매체_등록_수정_회수가_관리이력에_남는다() throws Exception {
        createAdmin("admin", "Passw0rd!");

        // 필수 칸(승인자)이 비면 저장 안 됨
        mvc.perform(post("/devices").with(csrf())
                        .param("instanceId", "USBSTOR\\DISK&VEN_A\\1").param("owner", "홍길동").param("purpose", "업무"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "approver"));
        assertThat(devices.count()).isZero();

        mvc.perform(post("/devices").with(csrf())
                        .param("instanceId", " USBSTOR\\DISK&VEN_A\\1 ").param("owner", "홍길동").param("purpose", "업무")
                        .param("approver", "김팀장").param("kind", "USB저장장치").param("pcName", "").param("expiresOn", "2027-12-31"))
                .andExpect(redirectedUrl("/devices"));
        AllowedDevice saved = devices.findAll().getFirst();
        assertThat(saved.getInstanceId()).isEqualTo("USBSTOR\\DISK&VEN_A\\1");
        assertThat(saved.getPcName()).isNull();
        assertThat(saved.getRegisteredBy()).isEqualTo("admin");

        mvc.perform(post("/devices/" + saved.getId()).with(csrf())
                        .param("instanceId", "USBSTOR\\DISK&VEN_A\\1").param("owner", "이몽룡").param("purpose", "업무")
                        .param("approver", "김팀장").param("kind", "USB저장장치"))
                .andExpect(redirectedUrl("/devices"));
        assertThat(devices.findById(saved.getId()).orElseThrow().getOwner()).isEqualTo("이몽룡");

        // 사유 없이 회수 안 됨
        mvc.perform(post("/devices/" + saved.getId() + "/revoke").with(csrf()).param("reason", " "))
                .andExpect(redirectedUrl("/devices/" + saved.getId() + "/edit"));
        mvc.perform(post("/devices/" + saved.getId() + "/revoke").with(csrf()).param("reason", "퇴사"))
                .andExpect(redirectedUrl("/devices"));
        AllowedDevice revoked = devices.findById(saved.getId()).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getRevokedReason()).isEqualTo("퇴사");

        assertThat(auditLogs.findAll()).extracting("action")
                .contains("허용 매체 등록", "허용 매체 수정", "허용 매체 회수");
        assertThat(auditLogs.findAll()).filteredOn(l -> l.getAction().equals("허용 매체 수정"))
                .first().satisfies(l -> assertThat(l.getDetail()).contains("홍길동").contains("이몽룡"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 웹_화면에서는_CSRF_토큰_없이_변경할_수_없다() throws Exception {
        mvc.perform(post("/devices").param("instanceId", "X").param("owner", "a").param("purpose", "b").param("approver", "c"))
                .andExpect(status().isForbidden());
        assertThat(devices.count()).isZero();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 모든_화면이_데이터가_있어도_열린다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        Pc pc = new Pc("OFFICE-01", LocalDateTime.now().minusDays(10));
        pc.setLastSeenAt(LocalDateTime.now());
        pc.setAppliedPolicyVersion("old");
        pcs.save(pc);
        AllowedDevice d = allow("USBSTOR\\DISK&VEN_A\\1");
        d.setExpiresOn(LocalDateTime.now().toLocalDate().plusDays(5));
        devices.save(d);
        UsbEvent e = events.save(new UsbEvent(LocalDateTime.now(), LocalDateTime.now(), "OFFICE-01", "OFFICE-01\\홍길동",
                "차단", "USB저장장치", "삼성 USB", "USBSTOR\\DISK&VEN_SAMSUNG\\1"));

        for (String url : new String[]{"/", "/devices", "/devices?status=전체", "/devices/new", "/devices/" + d.getId() + "/edit",
                "/events", "/pcs", "/reviews", "/reviews/new", "/audit", "/settings"}) {
            mvc.perform(get(url)).andExpect(status().isOk());
        }

        // 차단 기록에서 [허용 등록]을 누르면 장치 ID가 채워짐
        String html = mvc.perform(get("/devices/new").param("eventId", e.getId().toString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("USBSTOR\\DISK&amp;VEN_SAMSUNG\\1").contains("삼성 USB");

        String dashboard = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(dashboard).contains("최신 정책 적용 대기").contains("삼성 USB");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 관리대장을_엑셀용_CSV로_내려받는다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        AllowedDevice d = allow("USBSTOR\\DISK&VEN_A\\1");
        d.setPurpose("=HYPERLINK(\"http://evil\")");
        devices.save(d);

        var response = mvc.perform(get("/devices/export")).andExpect(status().isOk()).andReturn().getResponse();
        String csv = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿\"관리번호\",\"매체명\"");
        assertThat(csv).contains("\"홍길동\"").contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");
        assertThat(response.getHeader("Content-Disposition")).contains("filename*=UTF-8''");
        assertThat(auditLogs.findAll()).anyMatch(l -> l.getAction().equals("관리대장 내려받기"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 정기_점검을_저장하면_기간의_건수가_함께_남는다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        LocalDateTime t = LocalDateTime.of(2026, 9, 10, 10, 0);
        events.save(new UsbEvent(t, t, "PC1", "u", "차단", "USB저장장치", "a", "X1"));
        events.save(new UsbEvent(t, t, "PC1", "u", "차단(이미 차단된 장치)", "USB저장장치", "a", "X1"));
        events.save(new UsbEvent(t, t, "PC1", "u", "허용", "USB저장장치", "b", "X2"));
        events.save(new UsbEvent(t, t, "PC1", "u", "프로그램 제거", "", "", ""));                    // 허용 건수에 안 들어감
        events.save(new UsbEvent(t.plusMonths(1), t, "PC1", "u", "차단", "USB저장장치", "c", "X3")); // 기간 밖

        mvc.perform(post("/reviews").with(csrf()).param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("result", "이상 없음").param("memo", "차단 2건 확인"))
                .andExpect(redirectedUrl("/reviews"));

        var review = reviews.findAll().getFirst();
        assertThat(review.getBlockedCount()).isEqualTo(2);
        assertThat(review.getAllowedCount()).isEqualTo(1);
        assertThat(review.getReviewer()).isEqualTo("admin");
    }
}
