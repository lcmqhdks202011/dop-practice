package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 관리 화면 계정. 관리자마다 따로 만들어 씁니다(공용 계정 금지). */
@Entity
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    private int failedCount;
    private LocalDateTime lockedUntil;
    private LocalDateTime createdAt;
    private LocalDateTime passwordChangedAt;
    private LocalDateTime lastLoginAt;

    protected Admin() {
    }

    public Admin(String username, String passwordHash, LocalDateTime now) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = now;
        this.passwordChangedAt = now;
    }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public void changePassword(String passwordHash, LocalDateTime now) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = now;
    }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
