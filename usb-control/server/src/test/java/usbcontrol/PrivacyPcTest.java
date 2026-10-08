package usbcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import usbcontrol.domain.AllowedDevice;
import usbcontrol.domain.Pc;
import usbcontrol.domain.UsbEvent;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(username = "admin", roles = "ADMIN")
class PrivacyPcTest extends TestSupport {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode checkin(String pc, String agentVersion) throws Exception {
        String body = "{\"pcName\":\"" + pc + "\",\"userName\":\"u\",\"agentVersion\":\"" + agentVersion + "\",\"policyVersion\":\"\"}";
        String response = mvc.perform(post("/api/agent/checkin")
                        .header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private static List<String> list(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Pc privacyPc(String name) throws Exception {
        checkin(name, "2.2.0");
        Pc pc = pcs.findByNameIgnoreCase(name).orElseThrow();
        mvc.perform(post("/pcs/" + pc.getId() + "/privacy").with(csrf()).param("privacy", "true").param("handler", "인사팀 홍길동"))
                .andExpect(redirectedUrl("/pcs/" + pc.getId()));
        return pcs.findByNameIgnoreCase(name).orElseThrow();
    }

    private AllowedDevice allowFor(String instanceId, String pcName, boolean writeAllowed) {
        AllowedDevice d = allow(instanceId);
        d.setPcName(pcName);
        d.setExpiresOn(LocalDate.now().plusDays(7));
        d.setWriteAllowed(writeAllowed);
        return devices.save(d);
    }

    @Test
    void 처리자_없이는_지정할_수_없고_지정과_해제가_관리이력에_남는다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        checkin("HR-01", "2.2.0");
        Long id = pcs.findByNameIgnoreCase("HR-01").orElseThrow().getId();

        mvc.perform(post("/pcs/" + id + "/privacy").with(csrf()).param("privacy", "true").param("handler", " "))
                .andExpect(flash().attributeExists("error"));
        assertThat(pcs.findById(id).orElseThrow().isPrivacyPc()).isFalse();

        Pc pc = privacyPc("HR-01");
        assertThat(pc.isPrivacyPc()).isTrue();
        assertThat(pc.getPrivacyHandler()).isEqualTo("인사팀 홍길동");
        assertThat(pc.getPrivacySetBy()).isEqualTo("admin");

        mvc.perform(post("/pcs/" + id + "/privacy").with(csrf()).param("privacy", "false"))
                .andExpect(redirectedUrl("/pcs/" + id));
        assertThat(pcs.findById(id).orElseThrow().isPrivacyPc()).isFalse();
        assertThat(auditLogs.findAll()).extracting("action").contains("개인정보처리 PC 지정", "개인정보처리 PC 해제");
    }

    @Test
    void 개인정보처리_PC에는_그_PC_전용_매체만_내려가고_쓰기_허용_목록이_따로_간다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        devices.save(allow("USBSTOR\\DISK&VEN_ALL\\1"));                       // 전체 PC
        devices.save(allow("USBSTOR\\DISK&VEN_WILD*"));                       // 별표, 전체 PC
        allowFor("USBSTOR\\DISK&VEN_HR_WILD*", "HR-01", false);               // 별표, 이 PC
        allowFor("USBSTOR\\DISK&VEN_HR\\RO", "HR-01", false);
        allowFor("USBSTOR\\DISK&VEN_HR\\RW", "HR-01", true);

        JsonNode before = checkin("HR-01", "2.2.0");
        assertThat(before.get("privacyPc").asBoolean()).isFalse();
        assertThat(list(before.get("allow"))).contains("USBSTOR\\DISK&VEN_ALL\\1", "USBSTOR\\DISK&VEN_WILD*");
        assertThat(list(before.get("writable"))).isEmpty();

        privacyPc("HR-01");
        JsonNode after = checkin("HR-01", "2.2.0");
        assertThat(after.get("version").asText()).isNotEqualTo(before.get("version").asText());
        assertThat(after.get("privacyPc").asBoolean()).isTrue();
        assertThat(list(after.get("allow"))).containsExactly("USBSTOR\\DISK&VEN_HR\\RO", "USBSTOR\\DISK&VEN_HR\\RW");
        assertThat(list(after.get("writable"))).containsExactly("USBSTOR\\DISK&VEN_HR\\RW");

        // 다른 PC는 그대로
        assertThat(checkin("OFFICE-01", "2.2.0").get("privacyPc").asBoolean()).isFalse();

        assertThat(page("/pcs/" + pcs.findByNameIgnoreCase("HR-01").orElseThrow().getId()))
                .contains("전체 PC용(또는 별표) 허용 매체 3개는 이 PC에서 열리지 않습니다")
                .contains("쓰기 허용").contains("읽기 전용");
    }

    @Test
    void 개인정보처리_PC용_매체는_만료일이_필수이고_30일을_넘길_수_없다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        privacyPc("HR-01");
        checkin("OFFICE-01", "2.2.0");

        // 만료일 없음
        mvc.perform(post("/devices").with(csrf()).param("instanceId", "USBSTOR\\A").param("owner", "홍길동")
                        .param("purpose", "급여자료 은행 제출").param("approver", "김팀장").param("pcName", "HR-01"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "expiresOn"));
        // 30일 넘음 + 별표
        mvc.perform(post("/devices").with(csrf()).param("instanceId", "USBSTOR\\A*").param("owner", "홍길동")
                        .param("purpose", "업무").param("approver", "김팀장").param("pcName", "HR-01")
                        .param("expiresOn", LocalDate.now().plusDays(31).toString()))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "expiresOn", "instanceId"));
        // 일반 PC에 쓰기 허용 체크는 의미 없음
        mvc.perform(post("/devices").with(csrf()).param("instanceId", "USBSTOR\\B").param("owner", "홍길동")
                        .param("purpose", "업무").param("approver", "김팀장").param("pcName", "OFFICE-01")
                        .param("writeAllowed", "true"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "writeAllowed"));
        assertThat(devices.count()).isZero();

        mvc.perform(post("/devices").with(csrf()).param("instanceId", "USBSTOR\\A").param("owner", "홍길동")
                        .param("purpose", "급여자료 은행 제출").param("approver", "김팀장").param("pcName", "HR-01")
                        .param("expiresOn", LocalDate.now().plusDays(30).toString()).param("writeAllowed", "true"))
                .andExpect(redirectedUrl("/devices"));
        AllowedDevice saved = devices.findAll().getFirst();
        assertThat(saved.isWriteAllowed()).isTrue();
        assertThat(auditLogs.findAll()).filteredOn(l -> l.getAction().equals("허용 매체 등록"))
                .first().satisfies(l -> assertThat(l.getDetail()).contains("쓰기 허용"));

        String csv = mvc.perform(get("/devices/export")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"개인정보처리 PC\",\"쓰기\"").contains("\"HR-01\",\"예\",\"쓰기 허용\"");
    }

    @Test
    void 파일_반출_기록이_개인정보처리_PC_표시와_함께_저장되고_따로_볼_수_있다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        privacyPc("HR-01");
        String body = """
                {"pcName":"HR-01","events":[
                  {"occurredAt":"2026-10-07T09:15:30","userName":"HR-01\\\\홍길동","action":"파일 반출","kind":"USB저장장치",
                   "deviceName":"삼성 USB","instanceId":"USBSTOR\\\\DISK&VEN_SAMSUNG\\\\1",
                   "fileName":"E:\\\\급여\\\\2026-09 급여대장.xlsx","fileSize":1288490},
                  {"occurredAt":"2026-10-07T09:16:00","userName":"HR-01\\\\홍길동","action":"파일 반출 기록 누락","kind":"USB저장장치",
                   "deviceName":"삼성 USB","instanceId":"USBSTOR\\\\DISK&VEN_SAMSUNG\\\\1","fileName":"E:\\\\"}
                ]}
                """;
        mvc.perform(post("/api/agent/events").header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        events.save(new UsbEvent(java.time.LocalDateTime.of(2026, 10, 7, 9, 0), java.time.LocalDateTime.now(),
                "OFFICE-01", "u", "차단", "USB저장장치", "다른 USB", "USBSTOR\\X"));

        UsbEvent export = events.findAll().stream().filter(e -> UsbEvent.FILE_EXPORT.equals(e.getAction())).findFirst().orElseThrow();
        assertThat(export.isPrivacyPc()).isTrue();
        assertThat(export.getFileName()).isEqualTo("E:\\급여\\2026-09 급여대장.xlsx");
        assertThat(export.getFileSizeText()).isEqualTo("1.2 MB");

        String exports = page("/events?from=2026-10-07&to=2026-10-07&type=반출");
        assertThat(exports).contains("2026-09 급여대장.xlsx").contains("1.2 MB").contains("파일 반출 기록 누락")
                .doesNotContain("다른 USB");
        assertThat(page("/events?from=2026-10-07&to=2026-10-07&privacy=true")).doesNotContain("다른 USB");
        assertThat(page("/events?from=2026-10-07&to=2026-10-07&type=기타")).doesNotContain("급여대장");
        assertThat(page("/")).contains("최근 개인정보처리 PC 파일 반출").contains("급여대장");

        String csv = mvc.perform(get("/events/export").param("from", "2026-10-07").param("to", "2026-10-07")
                        .param("privacy", "true"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"파일\",\"파일 크기(바이트)\"").contains("\"1288490\"").doesNotContain("다른 USB");

        // 정기 점검에 개인정보처리 PC 건수가 따로 남음
        assertThat(page("/reviews/new?from=2026-10-01&to=2026-10-31")).contains("기간 중 파일 반출").contains("기록 누락 1건");
        mvc.perform(post("/reviews").with(csrf()).param("from", "2026-10-01").param("to", "2026-10-31")
                        .param("result", "이상 없음").param("memo", "반출 1건 확인, 승인된 은행 제출 건"))
                .andExpect(redirectedUrl("/reviews"));
        var review = reviews.findAll().getFirst();
        assertThat(review.getBlockedCount()).isEqualTo(1);
        assertThat(review.getPrivacyBlockedCount()).isZero();
        assertThat(review.getPrivacyExportCount()).isEqualTo(1);
    }

    @Test
    void 개인정보처리_PC_구분이_목록과_엑셀에_나오고_예전_프로그램이면_경고한다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        privacyPc("HR-01");
        checkin("HR-01", "2.1.0");
        checkin("OFFICE-01", "2.1.0");

        String list = page("/pcs");
        assertThat(list).contains("개인정보처리").contains("인사팀 홍길동")
                .contains("PC 프로그램 업데이트 필요 (읽기 전용·반출 기록 안 됨)");
        assertThat(page("/")).contains("개인정보처리 PC");

        String csv = mvc.perform(get("/pcs/export")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"HR-01\",\"개인정보처리 PC\",\"인사팀 홍길동\"").contains("\"OFFICE-01\",\"일반\"");
        assertThat(auditLogs.findAll()).anyMatch(l -> l.getAction().equals("PC 목록 내려받기"));
    }
}
