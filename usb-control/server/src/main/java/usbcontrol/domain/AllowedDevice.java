package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 허용 매체 (보조저장매체 관리대장의 한 줄) */
@Entity
public class AllowedDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 장치 ID. 별표(*)가 들어가면 여러 장치를 한 번에 허용 */
    @Column(nullable = false, length = 500)
    private String instanceId;

    private String deviceName;
    private String kind;
    private String owner;
    private String department;
    private String purpose;
    private String approver;

    /** 비어 있으면 모든 PC에서 허용 */
    private String pcName;

    private LocalDateTime registeredAt;
    private String registeredBy;
    private LocalDate expiresOn;
    /** 개인정보처리 PC에서도 쓰기(반출)를 허용. 다른 PC에서는 원래 쓰기가 됩니다. */
    @Column(columnDefinition = "boolean default false not null")
    private boolean writeAllowed;

    private boolean revoked;
    private LocalDateTime revokedAt;
    private String revokedBy;
    private String revokedReason;

    public boolean isExpired(LocalDate today) {
        return expiresOn != null && today.isAfter(expiresOn);
    }

    public boolean isActive(LocalDate today) {
        return !revoked && !isExpired(today);
    }

    public boolean appliesTo(String pc) {
        return pcName == null || pcName.isBlank() || pcName.equalsIgnoreCase(pc);
    }

    /** 개인정보처리 PC에는 그 PC 전용으로 등록한 매체만 (전체 PC용, 별표 줄은 안 됨) */
    public boolean appliesToPrivacyPc(String pc) {
        return pcName != null && pcName.equalsIgnoreCase(pc) && !isWildcard();
    }

    public String getStatus() {
        if (revoked) return "회수";
        if (isExpired(LocalDate.now())) return "만료";
        return "사용 중";
    }

    public boolean isWildcard() {
        return instanceId != null && instanceId.contains("*");
    }

    public Long getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getApprover() { return approver; }
    public void setApprover(String approver) { this.approver = approver; }
    public String getPcName() { return pcName; }
    public void setPcName(String pcName) { this.pcName = pcName; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(LocalDateTime registeredAt) { this.registeredAt = registeredAt; }
    public String getRegisteredBy() { return registeredBy; }
    public void setRegisteredBy(String registeredBy) { this.registeredBy = registeredBy; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }
    public boolean isWriteAllowed() { return writeAllowed; }
    public void setWriteAllowed(boolean writeAllowed) { this.writeAllowed = writeAllowed; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokedBy() { return revokedBy; }
    public void setRevokedBy(String revokedBy) { this.revokedBy = revokedBy; }
    public String getRevokedReason() { return revokedReason; }
    public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }
}
