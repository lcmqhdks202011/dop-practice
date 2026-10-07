package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 감시 프로그램이 설치된 PC */
@Entity
public class Pc {

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
}
