package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 관리자 작업 이력 (로그인, 허용 매체 등록/수정/회수, 설정 변경 등) */
@Entity
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime at;
    private String adminName;
    private String ipAddress;
    private String action;
    @Column(length = 2000)
    private String detail;

    protected AuditLog() {
    }

    public AuditLog(LocalDateTime at, String adminName, String ipAddress, String action, String detail) {
        this.at = at;
        this.adminName = adminName;
        this.ipAddress = ipAddress;
        this.action = action;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public LocalDateTime getAt() { return at; }
    public String getAdminName() { return adminName; }
    public String getIpAddress() { return ipAddress; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
}
