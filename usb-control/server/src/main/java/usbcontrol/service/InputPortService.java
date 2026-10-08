package usbcontrol.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import usbcontrol.domain.DesignatedPort;
import usbcontrol.domain.DesignatedPortRepository;
import usbcontrol.domain.Pc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 지정 포트에 키보드·마우스가 제대로 꽂혀 있는지 확인합니다. */
@Service
public class InputPortService {

    /** PC 프로그램이 알려 온, 지금 USB에 꽂혀 있는 키보드·마우스 */
    public record InputDevice(String kind, String name, String deviceId, String port, String portLabel) {
    }

    /** 지정 포트 하나의 지금 상태 */
    public record PortView(DesignatedPort rule, InputDevice current, String state, String level) {
        public boolean isChanged() { return "다른 장치로 바뀜".equals(state); }
    }

    /** 지금 꽂혀 있는 장치 하나와 지정 여부 */
    public record DeviceView(InputDevice device, boolean designated) {
    }

    /** PC 상태에 덧붙일 문제. 없으면 null */
    public record PortCheck(String problem, String level) {
    }

    private static final TypeReference<List<InputDevice>> LIST = new TypeReference<>() {
    };

    private final DesignatedPortRepository ports;
    private final ObjectMapper json;

    public InputPortService(DesignatedPortRepository ports, ObjectMapper json) {
        this.ports = ports;
        this.json = json;
    }

    public String toJson(List<InputDevice> devices) {
        try {
            return json.writeValueAsString(devices);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 지금 꽂혀 있는 키보드·마우스. 예전 버전 PC 프로그램이라 알려 오지 않으면 null */
    public List<InputDevice> currentDevices(Pc pc) {
        if (pc.getInputDevicesJson() == null) return null;
        try {
            return json.readValue(pc.getInputDevicesJson(), LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    public List<DesignatedPort> rulesFor(String pcName) {
        return ports.findByPcNameIgnoreCaseOrderByKindDescIdAsc(pcName);
    }

    public List<PortView> portViews(Pc pc) {
        List<InputDevice> current = currentDevices(pc);
        return rulesFor(pc.getName()).stream().map(rule -> {
            if (current == null) return new PortView(rule, null, "확인 안 됨", "muted");
            InputDevice d = find(current, rule.getKind(), rule.getPort());
            if (d == null) return new PortView(rule, null, "빠짐", "bad");
            if (!sameDevice(rule, d)) return new PortView(rule, d, "다른 장치로 바뀜", "bad");
            return new PortView(rule, d, "정상", "ok");
        }).toList();
    }

    public List<DeviceView> deviceViews(Pc pc) {
        List<InputDevice> current = currentDevices(pc);
        if (current == null) return List.of();
        List<DesignatedPort> rules = rulesFor(pc.getName());
        return current.stream()
                .map(d -> new DeviceView(d, rules.stream().anyMatch(r -> matches(r, d.kind(), d.port()))))
                .toList();
    }

    /** 키보드·마우스 문제를 한 줄로. 지정 포트가 없는 PC는 확인하지 않습니다. */
    public PortCheck check(Pc pc) {
        List<DesignatedPort> rules = rulesFor(pc.getName());
        if (rules.isEmpty()) return null;
        List<InputDevice> current = currentDevices(pc);
        if (current == null) return new PortCheck("PC 프로그램 업데이트 필요 (키보드·마우스 확인 안 됨)", "warn");

        List<String> bad = new ArrayList<>();
        for (DesignatedPort rule : rules) {
            InputDevice d = find(current, rule.getKind(), rule.getPort());
            if (d == null) bad.add(rule.getKind() + " 빠짐");
            else if (!sameDevice(rule, d)) bad.add(rule.getKind() + " 바뀜");
        }
        if (!bad.isEmpty()) return new PortCheck(String.join(", ", bad.stream().distinct().toList()), "bad");

        List<String> extra = current.stream()
                .filter(d -> rules.stream().noneMatch(r -> matches(r, d.kind(), d.port())))
                .map(d -> "지정 외 포트에 " + d.kind())
                .distinct()
                .toList();
        return extra.isEmpty() ? null : new PortCheck(String.join(", ", extra), "warn");
    }

    public static InputDevice find(List<InputDevice> devices, String kind, String port) {
        return devices.stream()
                .filter(d -> Objects.equals(d.kind(), kind) && port != null && port.equalsIgnoreCase(d.port()))
                .findFirst().orElse(null);
    }

    private static boolean matches(DesignatedPort rule, String kind, String port) {
        return rule.getKind().equals(kind) && rule.getPort().equalsIgnoreCase(port);
    }

    private static boolean sameDevice(DesignatedPort rule, InputDevice d) {
        return rule.getDeviceId() == null || rule.getDeviceId().isBlank() || rule.getDeviceId().equalsIgnoreCase(d.deviceId());
    }
}
