package usbcontrol.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import usbcontrol.domain.Settings;
import usbcontrol.domain.SettingsRepository;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SettingsService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SettingsRepository repository;

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Settings get() {
        return repository.findById(Settings.ID)
                .orElseGet(() -> repository.save(new Settings(newKey())));
    }

    @Transactional
    public String regenerateAgentKey() {
        Settings settings = get();
        settings.setAgentKey(newKey());
        return settings.getAgentKey();
    }

    @Transactional
    public void update(boolean blockPhones, boolean notifyUser, boolean installBlock) {
        Settings settings = get();
        settings.setBlockPhones(blockPhones);
        settings.setNotifyUser(notifyUser);
        settings.setInstallBlock(installBlock);
    }

    private static String newKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
