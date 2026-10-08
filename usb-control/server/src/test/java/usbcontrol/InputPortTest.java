package usbcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import usbcontrol.domain.Pc;
import usbcontrol.domain.UsbEvent;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InputPortTest extends TestSupport {

    private static final String KEYBOARD = """
            {"kind":"키보드","name":"USB Keyboard","deviceId":"USB\\\\VID_1A2C&PID_0E24\\\\5&2CF64626&0&5",
             "port":"PCIROOT(0)#PCI(1400)#USBROOT(0)#USB(5)","portLabel":"허브 1 - 포트 5"}""";
    private static final String MOUSE = """
            {"kind":"마우스","name":"USB Optical Mouse","deviceId":"USB\\\\VID_046D&PID_C077\\\\5&2CF64626&0&6",
             "port":"PCIROOT(0)#PCI(1400)#USBROOT(0)#USB(6)","portLabel":"허브 1 - 포트 6"}""";
    private static final String KEYLOGGER_ON_PORT_5 = """
            {"kind":"키보드","name":"Unknown Keyboard","deviceId":"USB\\\\VID_DEAD&PID_BEEF\\\\5&2CF64626&0&5",
             "port":"PCIROOT(0)#PCI(1400)#USBROOT(0)#USB(5)","portLabel":"허브 1 - 포트 5"}""";
    private static final String KEYBOARD_ON_PORT_2 = """
            {"kind":"키보드","name":"USB Keyboard","deviceId":"USB\\\\VID_1A2C&PID_0E24\\\\5&2CF64626&0&2",
             "port":"PCIROOT(0)#PCI(1400)#USBROOT(0)#USB(2)","portLabel":"허브 1 - 포트 2"}""";

    private final ObjectMapper json = new ObjectMapper();
    /** PC 프로그램처럼 마지막으로 받은 정책 버전을 다음 접속 때 알립니다. */
    private String appliedVersion = "";

    private JsonNode checkin(String inputDevicesJson) throws Exception {
        String body = "{\"pcName\":\"PC1\",\"userName\":\"PC1\\\\홍길동\",\"agentVersion\":\"2.1.0\",\"policyVersion\":\"" + appliedVersion + "\""
                + (inputDevicesJson == null ? "" : ",\"inputDevices\":" + inputDevicesJson) + "}";
        String response = mvc.perform(post("/api/agent/checkin")
                        .header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode policy = json.readTree(response);
        appliedVersion = policy.get("version").asText();
        return policy;
    }

    private Pc pc1() {
        return pcs.findByNameIgnoreCase("PC1").orElseThrow();
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 꽂힌_키보드_마우스를_저장하고_예전_버전은_비워둔다() throws Exception {
        checkin(null);
        assertThat(pc1().getInputDevicesJson()).isNull();

        checkin("[" + KEYBOARD + "," + MOUSE + ",{\"kind\":\"USB저장장치\",\"port\":\"X\"}]");
        assertThat(pc1().getInputDevicesJson()).contains("허브 1 - 포트 5").contains("USB Optical Mouse")
                .doesNotContain("USB저장장치");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 지정한_포트가_정책으로_내려가고_상태를_확인한다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        String v1 = checkin("[" + KEYBOARD + "," + MOUSE + "]").get("version").asText();
        Long id = pc1().getId();

        mvc.perform(post("/pcs/" + id + "/ports/all").with(csrf())).andExpect(redirectedUrl("/pcs/" + id));
        assertThat(designatedPortRepository.count()).isEqualTo(2);
        assertThat(auditLogs.findAll()).anyMatch(l -> l.getAction().equals("키보드·마우스 지정 포트 등록"));

        JsonNode policy = checkin("[" + KEYBOARD + "," + MOUSE + "]");
        assertThat(policy.get("version").asText()).isNotEqualTo(v1);
        assertThat(policy.get("ports")).hasSize(2);
        assertThat(policy.get("ports").get(0).get("kind").asText()).isEqualTo("키보드");
        assertThat(policy.get("ports").get(0).get("port").asText()).isEqualTo("PCIROOT(0)#PCI(1400)#USBROOT(0)#USB(5)");
        checkin("[" + KEYBOARD + "," + MOUSE + "]");                       // 새 정책을 적용한 뒤의 접속
        assertThat(page("/pcs")).contains(">정상<");

        // 키보드를 뽑음
        checkin("[" + MOUSE + "]");
        assertThat(page("/pcs")).contains("키보드 빠짐");
        assertThat(page("/pcs/" + id)).contains("빠짐");

        // 같은 포트에 다른 장치
        checkin("[" + KEYLOGGER_ON_PORT_5 + "," + MOUSE + "]");
        assertThat(page("/")).contains("키보드 바뀜");
        assertThat(page("/pcs/" + id)).contains("다른 장치로 바뀜").contains("Unknown Keyboard");

        // 원래 키보드는 제자리, 다른 포트에 키보드 하나 더
        checkin("[" + KEYBOARD + "," + MOUSE + "," + KEYBOARD_ON_PORT_2 + "]");
        assertThat(page("/pcs")).contains("지정 외 포트에 키보드");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 지정을_해제하면_확인하지_않는다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        checkin("[" + KEYBOARD + "]");
        Long id = pc1().getId();
        mvc.perform(post("/pcs/" + id + "/ports").with(csrf())
                        .param("kind", "키보드").param("port", "PCIROOT(0)#PCI(1400)#USBROOT(0)#USB(5)"))
                .andExpect(redirectedUrl("/pcs/" + id));
        checkin("[]");
        assertThat(page("/pcs")).contains("키보드 빠짐");

        Long portId = designatedPortRepository.findAll().getFirst().getId();
        mvc.perform(post("/pcs/" + id + "/ports/" + portId + "/delete").with(csrf()))
                .andExpect(redirectedUrl("/pcs/" + id));
        assertThat(designatedPortRepository.count()).isZero();
        assertThat(page("/pcs")).doesNotContain("키보드 빠짐");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 포트_기록은_빨간색으로_보이고_따로_골라_볼_수_있다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        LocalDateTime t = LocalDateTime.now().minusHours(1);
        events.save(new UsbEvent(t, t, "PC1", "u", "빠짐", "키보드", "USB Keyboard", "USB\\VID_1A2C", "허브 1 - 포트 5"));
        events.save(new UsbEvent(t, t, "PC1", "u", "다시 연결", "키보드", "USB Keyboard", "USB\\VID_1A2C", "허브 1 - 포트 5"));
        events.save(new UsbEvent(t, t, "PC1", "u", "차단", "USB저장장치", "삼성 USB", "USBSTOR\\X"));
        events.save(new UsbEvent(t, t, "PC1", "u", "프로그램 설치", "", "감시 프로그램", ""));

        String inputs = page("/events?type=입력장치");
        assertThat(inputs).contains("[허브 1 - 포트 5]").contains("다시 연결").doesNotContain("삼성 USB");
        assertThat(page("/events?type=기타")).contains("프로그램 설치").doesNotContain("USB Keyboard");
        assertThat(page("/")).contains("최근 키보드·마우스 포트 이상").contains("키보드 빠짐");

        String csv = mvc.perform(get("/events/export").param("from", t.toLocalDate().toString())
                        .param("to", t.toLocalDate().toString()).param("type", "입력장치"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"포트\"").contains("\"허브 1 - 포트 5\"");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 모든_USB_장치_연결_기록은_따로_골라_보고_포트_기록과_섞이지_않는다() throws Exception {
        createAdmin("admin", "Passw0rd!");
        String body = """
                {"pcName":"PC1","events":[
                  {"occurredAt":"2026-10-07T09:00:00","userName":"u","action":"USB 연결","kind":"키보드, USB저장장치",
                   "deviceName":"Rubber Ducky","instanceId":"USB\\\\VID_F000&PID_FF02\\\\1","port":"허브 1 - 포트 3"},
                  {"occurredAt":"2026-10-07T09:01:00","userName":"u","action":"USB 분리","kind":"키보드",
                   "deviceName":"USB Keyboard","instanceId":"USB\\\\VID_1A2C&PID_0E24\\\\5","port":"허브 1 - 포트 5"},
                  {"occurredAt":"2026-10-07T09:02:00","userName":"u","action":"프로그램 설치","kind":"","deviceName":"감시 프로그램","instanceId":""}
                ]}
                """;
        mvc.perform(post("/api/agent/events").header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        UsbEvent unplugged = events.findAll().stream().filter(e -> "USB 분리".equals(e.getAction())).findFirst().orElseThrow();
        assertThat(unplugged.isUsbDevice()).isTrue();
        assertThat(unplugged.isInputDevice()).isFalse();
        assertThat(unplugged.isAlert()).isFalse();

        String usb = page("/events?from=2026-10-07&to=2026-10-07&type=USB");
        assertThat(usb).contains("키보드, USB저장장치 - Rubber Ducky [허브 1 - 포트 3]").contains("USB 분리")
                .doesNotContain("감시 프로그램");
        assertThat(page("/events?from=2026-10-07&to=2026-10-07&type=입력장치")).doesNotContain("Rubber Ducky").doesNotContain("USB 분리");
        assertThat(page("/events?from=2026-10-07&to=2026-10-07&type=기타")).contains("감시 프로그램").doesNotContain("Rubber Ducky");
    }
}
