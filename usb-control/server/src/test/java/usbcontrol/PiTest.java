package usbcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import usbcontrol.domain.PiFinding;
import usbcontrol.domain.PiScan;
import usbcontrol.domain.UsbEvent;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(username = "admin", roles = "ADMIN")
class PiTest extends TestSupport {

    private final ObjectMapper json = new ObjectMapper();

    private static final String SCAN = """
            {"pcName":"PC1","trigger":"정기","startedAt":"2026-10-07T12:00:00","finishedAt":"2026-10-07T12:20:00",
             "scannedFiles":1520,"skippedFiles":3,"truncated":false,"findings":[
              {"path":"C:\\\\Users\\\\hong\\\\Documents\\\\고객명단.xlsx","size":48211,"modifiedAt":"2026-09-30T10:00:00",
               "counts":{"rrn":120,"foreigner":0,"passport":0,"driver":0,"card":0,"phone":118,"email":40,"account":0}},
              {"path":"C:\\\\Users\\\\hong\\\\Desktop\\\\연락처.csv","size":1200,"modifiedAt":"2026-09-01T09:00:00",
               "counts":{"rrn":0,"foreigner":0,"passport":0,"driver":0,"card":0,"phone":12,"email":3,"account":0}}
            ]}""";

    private void upload(String body) throws Exception {
        mvc.perform(post("/api/agent/pi-scan").header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
    }

    private JsonNode checkin() throws Exception {
        String response = mvc.perform(post("/api/agent/checkin").header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pcName\":\"PC1\",\"agentVersion\":\"2.4.0\",\"policyVersion\":\"\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 검사_결과를_받아_건수만_저장하고_조치를_남긴다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        checkin();
        upload(SCAN);

        PiScan scan = piScans.findAll().getFirst();
        assertThat(scan.getScannedFiles()).isEqualTo(1520);
        assertThat(scan.getDetectedFiles()).isEqualTo(2);
        PiFinding customers = piFindings.findAll().stream().filter(f -> f.getPath().endsWith("고객명단.xlsx")).findFirst().orElseThrow();
        assertThat(customers.getCounts().summary()).isEqualTo("주민등록번호 120, 휴대폰번호 118, 이메일 40");

        assertThat(page("/pi")).contains("PC1").contains("조치하지 않은 개인정보 파일이 2개");
        assertThat(page("/")).contains("조치 안 한 개인정보 파일");
        assertThat(page("/pi/scans/" + scan.getId())).contains("고객명단.xlsx").contains("주민등록번호 120");

        mvc.perform(post("/pi/findings/" + customers.getId() + "/action").with(csrf())
                        .param("result", "업무상 보관 (승인)").param("memo", "김팀장 승인, 암호 설정"))
                .andExpect(redirectedUrl("/pi/scans/" + scan.getId()));
        mvc.perform(post("/pi/findings/" + customers.getId() + "/action").with(csrf()).param("result", "아무거나"))
                .andExpect(flash().attributeExists("error"));
        assertThat(piFindings.findById(customers.getId()).orElseThrow().getActionBy()).isEqualTo("admin");
        assertThat(auditLogs.findAll()).anyMatch(l -> l.getAction().equals("개인정보 파일 조치"));

        // 다음 검사: '업무상 보관'은 이어지고, 나머지는 다시 미조치
        upload(SCAN.replace("2026-10-07T12", "2026-10-14T12"));
        PiScan second = piScans.findAll().stream().max(Comparator.comparing(PiScan::getFinishedAt)).orElseThrow();
        assertThat(piFindings.findByScanIdOrderByIdAsc(second.getId()))
                .extracting(PiFinding::getActionResult).containsExactly("업무상 보관 (승인)", null);
        assertThat(page("/pi")).contains("조치하지 않은 개인정보 파일이 1개");

        String csv = mvc.perform(get("/pi/export")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"주민등록번호\",\"외국인등록번호\"").contains("\"업무상 보관 (승인)\"").contains("\"미조치\"")
                .doesNotContain("2026-10-07T12:20");   // 최근 검사만
    }

    @Test
    void 지금_검사와_주기가_정책으로_내려간다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        JsonNode p1 = checkin();
        assertThat(p1.get("piScanDays").asInt()).isEqualTo(7);
        assertThat(p1.get("piScanRequest").asText()).isEmpty();

        Long id = pcs.findByNameIgnoreCase("PC1").orElseThrow().getId();
        mvc.perform(post("/pi/pcs/" + id + "/request").with(csrf())).andExpect(redirectedUrl("/pi"));
        JsonNode p2 = checkin();
        assertThat(p2.get("piScanRequest").asText()).isNotEmpty();
        assertThat(p2.get("version").asText()).isNotEqualTo(p1.get("version").asText());
        assertThat(page("/pi")).contains("검사 요청됨");

        mvc.perform(post("/settings/pi-scan").with(csrf()).param("piScanDays", "0")).andExpect(redirectedUrl("/settings"));
        assertThat(checkin().get("piScanDays").asInt()).isZero();
        assertThat(auditLogs.findAll()).extracting("action").contains("개인정보 검사 요청", "개인정보 검사 주기 변경");
    }

    @Test
    void 반출한_파일의_개인정보가_기록에_남고_빨간색으로_보인다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        String body = """
                {"pcName":"HR-01","events":[
                  {"occurredAt":"2026-10-07T09:15:30","userName":"u","action":"파일 반출","kind":"USB저장장치","deviceName":"삼성 USB",
                   "instanceId":"USBSTOR\\\\A","fileName":"E:\\\\급여대장.xlsx","fileSize":1000,
                   "piCounts":{"rrn":30,"foreigner":0,"passport":0,"driver":0,"card":0,"phone":0,"email":0,"account":30}},
                  {"occurredAt":"2026-10-07T09:16:00","userName":"u","action":"파일 반출","kind":"USB저장장치","deviceName":"삼성 USB",
                   "instanceId":"USBSTOR\\\\A","fileName":"E:\\\\회의록.docx","fileSize":1000,
                   "piCounts":{"rrn":0,"foreigner":0,"passport":0,"driver":0,"card":0,"phone":1,"email":1,"account":0}},
                  {"occurredAt":"2026-10-07T09:17:00","userName":"u","action":"파일 반출","kind":"USB저장장치","deviceName":"삼성 USB",
                   "instanceId":"USBSTOR\\\\A","fileName":"E:\\\\비밀.xlsx","fileSize":1000,"piNote":"암호 걸린 파일"}
                ]}""";
        mvc.perform(post("/api/agent/events").header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        UsbEvent payroll = events.findAll().stream().filter(e -> "E:\\급여대장.xlsx".equals(e.getFileName())).findFirst().orElseThrow();
        assertThat(payroll.isPiDetected()).isTrue();
        assertThat(payroll.isAlert()).isTrue();
        assertThat(payroll.getPiSummary()).isEqualTo("주민등록번호 30, 계좌번호 30");

        String detected = page("/events?from=2026-10-07&to=2026-10-07&type=개인정보");
        assertThat(detected).contains("급여대장.xlsx").doesNotContain("회의록.docx").doesNotContain("비밀.xlsx");
        assertThat(page("/events?from=2026-10-07&to=2026-10-07&type=반출"))
                .contains("휴대폰번호 1, 이메일 1").contains("검사 못 함: 암호 걸린 파일");
    }
}
