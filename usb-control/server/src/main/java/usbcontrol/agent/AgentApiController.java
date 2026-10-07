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

    public record CheckinRequest(String pcName, String userName, String agentVersion,
                                 String policyVersion, String os) {
    }

    public record EventItem(LocalDateTime occurredAt, String userName, String action,
                            String kind, String deviceName, String instanceId) {
    }

    public record EventsRequest(String pcName, List<EventItem> events) {
    }

    public record EventsResponse(int saved) {
    }

    private final SettingsService settings;
    private final PolicyService policies;
    private final PcRepository pcs;
    private final UsbEventRepository events;

    public AgentApiController(SettingsService settings, PolicyService policies,
                              PcRepository pcs, UsbEventRepository events) {
        this.settings = settings;
        this.policies = policies;
        this.pcs = pcs;
        this.events = events;
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
                        cut(e.deviceName(), 255), cut(e.instanceId(), 500)))
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
