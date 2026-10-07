package usbcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import usbcontrol.domain.AllowedDevice;
import usbcontrol.domain.UsbEvent;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentApiTest extends TestSupport {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode checkin(String pc, String policyVersion) throws Exception {
        String body = """
                {"pcName":"%s","userName":"OFFICE-01\\\\홍길동","agentVersion":"1.0.0","policyVersion":"%s","os":"Windows 11 Pro"}
                """.formatted(pc, policyVersion);
        String response = mvc.perform(post("/api/agent/checkin")
                        .header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private List<String> allowOf(JsonNode policy) {
        List<String> list = new ArrayList<>();
        policy.get("allow").forEach(n -> list.add(n.asText()));
        return list;
    }

    @Test
    void 키가_없거나_틀리면_거절() throws Exception {
        String body = "{\"pcName\":\"PC1\"}";
        mvc.perform(post("/api/agent/checkin").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/checkin").header("X-Agent-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/events").header("X-Agent-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pcName\":\"PC1\",\"events\":[]}"))
                .andExpect(status().isUnauthorized());
        assertThat(pcs.count()).isZero();
    }

    @Test
    void 처음_접속하면_PC가_등록되고_빈_허용목록을_받는다() throws Exception {
        JsonNode policy = checkin("OFFICE-01", "");

        assertThat(allowOf(policy)).isEmpty();
        assertThat(policy.get("blockPhones").asBoolean()).isTrue();
        var pc = pcs.findByNameIgnoreCase("office-01").orElseThrow();
        assertThat(pc.getLastUser()).isEqualTo("OFFICE-01\\홍길동");
        assertThat(pc.getOs()).isEqualTo("Windows 11 Pro");
        assertThat(pc.getLastSeenAt()).isNotNull();
    }

    @Test
    void 사용중인_매체만_해당_PC에_내려간다() throws Exception {
        devices.save(allow("USBSTOR\\DISK&VEN_A\\1"));                       // 전체 PC

        AllowedDevice onlyPc2 = allow("USBSTOR\\DISK&VEN_B\\2");
        onlyPc2.setPcName("OFFICE-02");
        devices.save(onlyPc2);

        AllowedDevice expired = allow("USBSTOR\\DISK&VEN_C\\3");
        expired.setExpiresOn(LocalDate.now().minusDays(1));
        devices.save(expired);

        AllowedDevice expiresToday = allow("USBSTOR\\DISK&VEN_D\\4");          // 오늘까지는 사용 가능
        expiresToday.setExpiresOn(LocalDate.now());
        devices.save(expiresToday);

        AllowedDevice revoked = allow("USBSTOR\\DISK&VEN_E\\5");
        revoked.setRevoked(true);
        devices.save(revoked);

        assertThat(allowOf(checkin("OFFICE-01", "")))
                .containsExactly("USBSTOR\\DISK&VEN_A\\1", "USBSTOR\\DISK&VEN_D\\4");
        assertThat(allowOf(checkin("office-02", "")))
                .containsExactly("USBSTOR\\DISK&VEN_A\\1", "USBSTOR\\DISK&VEN_B\\2", "USBSTOR\\DISK&VEN_D\\4");
    }

    @Test
    void 허용목록이나_설정이_바뀌면_정책_버전이_바뀐다() throws Exception {
        String v1 = checkin("PC1", "").get("version").asText();
        assertThat(checkin("PC1", v1).get("version").asText()).isEqualTo(v1);
        assertThat(pcs.findByNameIgnoreCase("PC1").orElseThrow().getAppliedPolicyVersion()).isEqualTo(v1);

        devices.save(allow("USBSTOR\\DISK&VEN_A\\1"));
        String v2 = checkin("PC1", v1).get("version").asText();
        assertThat(v2).isNotEqualTo(v1);

        settings.update(false, true, true);
        JsonNode p3 = checkin("PC1", v2);
        assertThat(p3.get("version").asText()).isNotEqualTo(v2);
        assertThat(p3.get("blockPhones").asBoolean()).isFalse();
        assertThat(p3.get("installBlock").asBoolean()).isTrue();
    }

    @Test
    void 기록을_올리면_한글까지_그대로_저장된다() throws Exception {
        String body = """
                {"pcName":"PC1","events":[
                  {"occurredAt":"2026-10-07T09:15:30","userName":"PC1\\\\홍길동","action":"차단","kind":"USB저장장치",
                   "deviceName":"삼성 USB","instanceId":"USBSTOR\\\\DISK&VEN_SAMSUNG\\\\0374&0"},
                  {"occurredAt":"2026-10-07T09:20:00","userName":"PC1\\\\홍길동","action":"허용","kind":"휴대폰",
                   "deviceName":"Galaxy","instanceId":"USB\\\\VID_04E8&PID_6860&MI_00\\\\R3C"}
                ]}
                """;
        mvc.perform(post("/api/agent/events").header("X-Agent-Key", settings.get().getAgentKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        List<UsbEvent> saved = events.findAll();
        assertThat(saved).hasSize(2);
        UsbEvent blocked = saved.stream().filter(UsbEvent::isBlocked).findFirst().orElseThrow();
        assertThat(blocked.getDeviceName()).isEqualTo("삼성 USB");
        assertThat(blocked.getUserName()).isEqualTo("PC1\\홍길동");
        assertThat(blocked.getInstanceId()).isEqualTo("USBSTOR\\DISK&VEN_SAMSUNG\\0374&0");
        assertThat(blocked.getOccurredAt().toString()).isEqualTo("2026-10-07T09:15:30");
        assertThat(pcs.findByNameIgnoreCase("PC1")).isPresent();
    }
}
