package usbcontrol;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import usbcontrol.domain.*;
import usbcontrol.service.SettingsService;

import java.time.LocalDateTime;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class TestSupport {

    @Autowired protected MockMvc mvc;
    @Autowired protected AllowedDeviceRepository devices;
    @Autowired protected PcRepository pcs;
    @Autowired protected UsbEventRepository events;
    @Autowired protected AuditLogRepository auditLogs;
    @Autowired protected ReviewRepository reviews;
    @Autowired protected AdminRepository admins;
    @Autowired protected SettingsRepository settingsRepository;
    @Autowired protected SettingsService settings;
    @Autowired protected PasswordEncoder encoder;

    @BeforeEach
    void cleanDatabase() {
        devices.deleteAll();
        pcs.deleteAll();
        events.deleteAll();
        auditLogs.deleteAll();
        reviews.deleteAll();
        admins.deleteAll();
        settingsRepository.deleteAll();
    }

    protected Admin createAdmin(String username, String password) {
        return admins.save(new Admin(username, encoder.encode(password), LocalDateTime.now()));
    }

    protected AllowedDevice allow(String instanceId) {
        AllowedDevice d = new AllowedDevice();
        d.setInstanceId(instanceId);
        d.setDeviceName("테스트 USB");
        d.setKind("USB저장장치");
        d.setOwner("홍길동");
        d.setPurpose("업무 자료 전달");
        d.setApprover("김팀장");
        d.setRegisteredAt(LocalDateTime.now());
        d.setRegisteredBy("admin");
        return d;
    }
}
