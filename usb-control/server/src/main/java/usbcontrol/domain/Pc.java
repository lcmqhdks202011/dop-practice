package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 감시 프로그램이 설치된 PC */
@Entity
public class Pc {

    /** 개인정보처리 PC용 허용 매체의 최대 사용 기간 (일) */
    public static final int PRIVACY_DEVICE_MAX_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String lastUser;
    private String ipAddress;
    private String os;
    private String agentVersion;
    private String appliedPolicyVersion;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    /** 지금 USB에 꽂혀 있는 키보드·마우스 (JSON). 예전 버전 PC 프로그램은 알려 오지 않아 null */
    @Column(length = 8000)
    private String inputDevicesJson;

    /** 개인정보처리 PC: 이 PC 전용으로 등록한 매체만 허용, 저장장치는 읽기 전용, USB로 복사한 파일 기록 */
    @Column(columnDefinition = "boolean default false not null")
    private boolean privacyPc;
    /** 이 PC로 개인정보를 처리하는 사람 */
    private String privacyHandler;
    private LocalDateTime privacySetAt;
    private String privacySetBy;

    protected Pc() {
    }

    public Pc(String name, LocalDateTime now) {
        this.name = name;
        this.firstSeenAt = now;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getLastUser() { return lastUser; }
    public void setLastUser(String lastUser) { this.lastUser = lastUser; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public String getAppliedPolicyVersion() { return appliedPolicyVersion; }
    public void setAppliedPolicyVersion(String appliedPolicyVersion) { this.appliedPolicyVersion = appliedPolicyVersion; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getInputDevicesJson() { return inputDevicesJson; }
    public void setInputDevicesJson(String inputDevicesJson) { this.inputDevicesJson = inputDevicesJson; }
    public boolean isPrivacyPc() { return privacyPc; }
    public void setPrivacyPc(boolean privacyPc) { this.privacyPc = privacyPc; }
    public String getPrivacyHandler() { return privacyHandler; }
    public void setPrivacyHandler(String privacyHandler) { this.privacyHandler = privacyHandler; }
    public LocalDateTime getPrivacySetAt() { return privacySetAt; }
    public void setPrivacySetAt(LocalDateTime privacySetAt) { this.privacySetAt = privacySetAt; }
    public String getPrivacySetBy() { return privacySetBy; }
    public void setPrivacySetBy(String privacySetBy) { this.privacySetBy = privacySetBy; }
}
