package usbcontrol.agent;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import usbcontrol.domain.Pc;
import usbcontrol.domain.PcRepository;
import usbcontrol.domain.UsbEvent;
import usbcontrol.domain.UsbEventRepository;
import usbcontrol.service.InputPortService;
import usbcontrol.service.InputPortService.InputDevice;
import usbcontrol.service.PolicyService;
import usbcontrol.service.PolicyService.AgentPolicy;
import usbcontrol.service.SettingsService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/** 각 PC의 감시 프로그램이 접속하는 곳 */
@RestController
@RequestMapping(path = "/api/agent", produces = "application/json;charset=UTF-8")
public class AgentApiController {

    public static final String KEY_HEADER = "X-Agent-Key";

    /** inputDevices: 지금 꽂혀 있는 키보드·마우스 (2.1.0 이상). 예전 버전은 null */
    public record CheckinRequest(String pcName, String userName, String agentVersion,
                                 String policyVersion, String os, List<InputDevice> inputDevices) {
    }

    /** fileName, fileSize: 개인정보처리 PC에서 USB로 복사한 파일 (2.2.0 이상) */
    public record EventItem(LocalDateTime occurredAt, String userName, String action,
                            String kind, String deviceName, String instanceId, String port,
                            String fileName, Long fileSize) {
    }

    private static final int MAX_INPUT_DEVICES = 20;

    public record EventsRequest(String pcName, List<EventItem> events) {
    }

    public record EventsResponse(int saved) {
    }

    private final SettingsService settings;
    private final PolicyService policies;
    private final PcRepository pcs;
    private final UsbEventRepository events;
    private final InputPortService inputPorts;

    public AgentApiController(SettingsService settings, PolicyService policies,
                              PcRepository pcs, UsbEventRepository events, InputPortService inputPorts) {
        this.settings = settings;
        this.policies = policies;
        this.pcs = pcs;
        this.events = events;
        this.inputPorts = inputPorts;
    }

    /** 30초마다: PC 상태를 알리고, 최신 허용 목록과 설정을 받아 갑니다. */
    @PostMapping(path = "/checkin", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<AgentPolicy> checkin(@RequestHeader(name = KEY_HEADER, required = false) String key,
                                               @RequestBody CheckinRequest body, HttpServletRequest request) {
        if (!validKey(key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (blank(body.pcName())) return ResponseEntity.badRequest().build();

        LocalDateTime now = LocalDateTime.now();
        Pc pc = findOrCreate(body.pcName(), now);
        pc.setLastSeenAt(now);
        pc.setLastUser(cut(body.userName(), 255));
        pc.setAgentVersion(cut(body.agentVersion(), 255));
        pc.setAppliedPolicyVersion(cut(body.policyVersion(), 255));
        pc.setOs(cut(body.os(), 255));
        pc.setIpAddress(request.getRemoteAddr());
        pc.setInputDevicesJson(body.inputDevices() == null ? null : inputPorts.toJson(body.inputDevices().stream()
                .filter(d -> d != null && UsbEvent.INPUT_KINDS.contains(d.kind()) && !blank(d.port()))
                .limit(MAX_INPUT_DEVICES)
                .map(d -> new InputDevice(d.kind(), cut(d.name(), 100), cut(d.deviceId(), 200),
                        cut(d.port(), 200), cut(d.portLabel(), 50)))
                .toList()));
        return ResponseEntity.ok(policies.policyFor(pc.getName()));
    }

    /** 장치 연결/차단 기록을 모아서 올립니다. */
    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<EventsResponse> events(@RequestHeader(name = KEY_HEADER, required = false) String key,
                                                 @RequestBody EventsRequest body) {
        if (!validKey(key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (blank(body.pcName()) || body.events() == null) return ResponseEntity.badRequest().build();

        LocalDateTime now = LocalDateTime.now();
        Pc pc = findOrCreate(body.pcName(), now);
        List<UsbEvent> saved = body.events().stream()
                .map(e -> new UsbEvent(
                        e.occurredAt() == null ? now : e.occurredAt(), now, pc.getName(),
                        cut(e.userName(), 255), cut(e.action(), 255), cut(e.kind(), 255),
                        cut(e.deviceName(), 255), cut(e.instanceId(), 500), cut(e.port(), 255))
                        .withFile(cut(e.fileName(), 1000), e.fileSize())
                        .withPrivacyPc(pc.isPrivacyPc()))
                .toList();
        events.saveAll(saved);
        return ResponseEntity.ok(new EventsResponse(saved.size()));
    }

    private Pc findOrCreate(String name, LocalDateTime now) {
        String trimmed = cut(name.trim(), 255);
        return pcs.findByNameIgnoreCase(trimmed).orElseGet(() -> pcs.save(new Pc(trimmed, now)));
    }

    private boolean validKey(String key) {
        if (key == null) return false;
        byte[] expected = settings.get().getAgentKey().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, key.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
