package usbcontrol.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import usbcontrol.domain.AllowedDevice;
import usbcontrol.domain.AllowedDeviceRepository;
import usbcontrol.domain.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/** PC 한 대에 내려보낼 차단 정책을 계산합니다. */
@Service
public class PolicyService {

    public record AgentPolicy(String version, boolean blockPhones, boolean notifyUser,
                              boolean installBlock, List<String> allow) {
    }

    private final AllowedDeviceRepository devices;
    private final SettingsService settingsService;

    public PolicyService(AllowedDeviceRepository devices, SettingsService settingsService) {
        this.devices = devices;
        this.settingsService = settingsService;
    }

    @Transactional
    public AgentPolicy policyFor(String pcName) {
        LocalDate today = LocalDate.now();
        List<String> allow = devices.findByRevokedFalse().stream()
                .filter(d -> d.isActive(today) && d.appliesTo(pcName))
                .map(AllowedDevice::getInstanceId)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        Settings s = settingsService.get();
        String version = hash(String.join("\n", allow)
                + "|" + s.isBlockPhones() + "|" + s.isNotifyUser() + "|" + s.isInstallBlock());
        return new AgentPolicy(version, s.isBlockPhones(), s.isNotifyUser(), s.isInstallBlock(), allow);
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
