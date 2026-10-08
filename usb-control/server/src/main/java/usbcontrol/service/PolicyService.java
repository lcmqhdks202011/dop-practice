package usbcontrol.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import usbcontrol.domain.AllowedDevice;
import usbcontrol.domain.AllowedDeviceRepository;
import usbcontrol.domain.DesignatedPortRepository;
import usbcontrol.domain.Pc;
import usbcontrol.domain.PcRepository;
import usbcontrol.domain.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** PC 한 대에 내려보낼 차단 정책을 계산합니다. */
@Service
public class PolicyService {

    /** privacyPc: 개인정보처리 PC. 저장장치를 읽기 전용으로 열고(writable 에 있는 매체만 쓰기 가능) USB로 복사한 파일을 기록합니다.
     *  piScanDays: 저장 파일 개인정보 검사 주기 (0이면 안 함), piScanRequest: 관리자가 [지금 검사]를 누른 때 (바뀌면 바로 검사) */
    public record AgentPolicy(String version, boolean blockPhones, boolean notifyUser,
                              boolean installBlock, List<String> allow, List<PortRule> ports,
                              boolean privacyPc, List<String> writable, int piScanDays, String piScanRequest) {
    }

    /** 키보드·마우스를 꽂아 두어야 하는 포트 */
    public record PortRule(String kind, String port, String portLabel, String deviceId, String deviceName) {
    }

    private final AllowedDeviceRepository devices;
    private final DesignatedPortRepository designatedPorts;
    private final PcRepository pcs;
    private final SettingsService settingsService;

    public PolicyService(AllowedDeviceRepository devices, DesignatedPortRepository designatedPorts,
                         PcRepository pcs, SettingsService settingsService) {
        this.devices = devices;
        this.designatedPorts = designatedPorts;
        this.pcs = pcs;
        this.settingsService = settingsService;
    }

    @Transactional
    public AgentPolicy policyFor(String pcName) {
        LocalDate today = LocalDate.now();
        Pc pc = pcs.findByNameIgnoreCase(pcName).orElse(null);
        boolean privacy = pc != null && pc.isPrivacyPc();
        String piScanRequest = pc == null || pc.getPiScanRequestedAt() == null ? "" : pc.getPiScanRequestedAt().toString();
        List<AllowedDevice> active = devices.findByRevokedFalse().stream()
                .filter(d -> d.isActive(today) && (privacy ? d.appliesToPrivacyPc(pcName) : d.appliesTo(pcName)))
                .toList();
        List<String> allow = idsOf(active);
        List<String> writable = privacy ? idsOf(active.stream().filter(AllowedDevice::isWriteAllowed).toList()) : List.of();
        List<PortRule> ports = designatedPorts.findByPcNameIgnoreCaseOrderByKindDescIdAsc(pcName).stream()
                .map(p -> new PortRule(p.getKind(), p.getPort(), p.getPortLabel(), p.getDeviceId(), p.getDeviceName()))
                .toList();
        Settings s = settingsService.get();
        String version = hash(String.join("\n", allow)
                + "|" + s.isBlockPhones() + "|" + s.isNotifyUser() + "|" + s.isInstallBlock()
                + ports.stream().map(p -> "|" + p.kind() + "|" + p.port() + "|" + p.deviceId()).collect(Collectors.joining())
                + (privacy ? "|privacy|" + String.join("\n", writable) : "")
                + "|pi|" + s.getPiScanDays() + "|" + piScanRequest);
        return new AgentPolicy(version, s.isBlockPhones(), s.isNotifyUser(), s.isInstallBlock(), allow, ports,
                privacy, writable, s.getPiScanDays(), piScanRequest);
    }

    private static List<String> idsOf(List<AllowedDevice> list) {
        return list.stream()
                .map(AllowedDevice::getInstanceId)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
