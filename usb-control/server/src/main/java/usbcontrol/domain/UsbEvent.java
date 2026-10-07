package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** PC에서 올라온 USB 연결 기록 */
@Entity
@Table(indexes = @Index(columnList = "occurredAt"))
public class UsbEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime occurredAt;
    private LocalDateTime receivedAt;
    private String pcName;
    private String userName;
    /** 허용 / 차단 / 허용(차단 풀림) / 차단(이미 차단된 장치) / 프로그램 설치 / 프로그램 제거 */
    private String action;
    private String kind;
    private String deviceName;
    @Column(length = 500)
    private String instanceId;

    protected UsbEvent() {
    }

    public UsbEvent(LocalDateTime occurredAt, LocalDateTime receivedAt, String pcName, String userName,
                    String action, String kind, String deviceName, String instanceId) {
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.pcName = pcName;
        this.userName = userName;
        this.action = action;
        this.kind = kind;
        this.deviceName = deviceName;
        this.instanceId = instanceId;
    }

    public boolean isBlocked() {
        return action != null && action.startsWith("차단");
    }

    public boolean isAllowed() {
        return action != null && action.startsWith("허용");
    }

    public Long getId() { return id; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public String getPcName() { return pcName; }
    public String getUserName() { return userName; }
    public String getAction() { return action; }
    public String getKind() { return kind; }
    public String getDeviceName() { return deviceName; }
    public String getInstanceId() { return instanceId; }
}
