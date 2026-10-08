package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** PC마다 키보드·마우스를 꽂아 두어야 하는 USB 포트 */
@Entity
@Table(indexes = @Index(columnList = "pcName"))
public class DesignatedPort {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String pcName;
    /** 키보드 / 마우스 */
    @Column(nullable = false)
    private String kind;
    /** 포트 위치 (예: PCIROOT(0)#PCI(1400)#USBROOT(0)#USB(3)) */
    @Column(nullable = false, length = 500)
    private String port;
    /** 사람이 읽는 포트 이름 (예: 허브 1 - 포트 3) */
    private String portLabel;
    /** 지정할 때 꽂혀 있던 장치. 같은 포트에 다른 장치가 꽂히면 '바뀜'으로 기록합니다. */
    @Column(length = 500)
    private String deviceId;
    private String deviceName;

    private LocalDateTime registeredAt;
    private String registeredBy;

    protected DesignatedPort() {
    }

    public DesignatedPort(String pcName, String kind, String port) {
        this.pcName = pcName;
        this.kind = kind;
        this.port = port;
    }

    public Long getId() { return id; }
    public String getPcName() { return pcName; }
    public String getKind() { return kind; }
    public String getPort() { return port; }
    public String getPortLabel() { return portLabel; }
    public void setPortLabel(String portLabel) { this.portLabel = portLabel; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(LocalDateTime registeredAt) { this.registeredAt = registeredAt; }
    public String getRegisteredBy() { return registeredBy; }
    public void setRegisteredBy(String registeredBy) { this.registeredBy = registeredBy; }
}
