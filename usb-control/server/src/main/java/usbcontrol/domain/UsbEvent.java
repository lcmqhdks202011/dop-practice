package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

/** PC에서 올라온 USB 연결 기록 */
@Entity
@Table(indexes = @Index(columnList = "occurredAt"))
public class UsbEvent {

    /** 지정 포트를 확인하는 장치 종류 */
    public static final List<String> INPUT_KINDS = List.of("키보드", "마우스");
    /** 키보드·마우스 기록 중 관리자가 확인해야 하는 것 (나머지: 다시 연결 / 지정 외 포트에서 빠짐) */
    public static final List<String> INPUT_ALERTS = List.of("빠짐", "다른 장치로 바뀜", "지정 외 포트에 연결");
    /** 개인정보처리 PC에서 허용 USB로 파일을 복사함 */
    public static final String FILE_EXPORT = "파일 반출";
    /** 한꺼번에 너무 많이 복사해서 파일 반출 기록 일부를 놓침 */
    public static final String FILE_EXPORT_MISSED = "파일 반출 기록 누락";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime occurredAt;
    private LocalDateTime receivedAt;
    private String pcName;
    private String userName;
    /** 허용 / 차단 / 허용(차단 풀림) / 차단(이미 차단된 장치) / 프로그램 설치 / 프로그램 제거
     *  키보드·마우스: 빠짐 / 다시 연결 / 다른 장치로 바뀜 / 지정 외 포트에 연결 / 지정 외 포트에서 빠짐
     *  개인정보처리 PC: 허용(읽기 전용) / 파일 반출 / 파일 반출 기록 누락
     *  모든 USB 장치: USB 연결 / USB 연결(설치 때 연결되어 있음) / USB 분리 */
    private String action;
    private String kind;
    private String deviceName;
    @Column(length = 500)
    private String instanceId;
    /** 키보드·마우스가 꽂힌 포트 (예: 허브 1 - 포트 3) */
    private String port;
    /** USB로 복사한 파일 (예: E:\고객명단.xlsx) */
    @Column(length = 1000)
    private String fileName;
    private Long fileSize;
    /** 기록을 받을 때 그 PC가 개인정보처리 PC였는지 */
    @Column(columnDefinition = "boolean default false not null")
    private boolean privacyPc;
    /** 반출한 파일에서 찾은 개인정보 (예: 주민등록번호 3, 휴대폰번호 12) 또는 검사하지 못한 이유 */
    private String piSummary;
    @Column(columnDefinition = "boolean default false not null")
    private boolean piDetected;

    protected UsbEvent() {
    }

    public UsbEvent(LocalDateTime occurredAt, LocalDateTime receivedAt, String pcName, String userName,
                    String action, String kind, String deviceName, String instanceId) {
        this(occurredAt, receivedAt, pcName, userName, action, kind, deviceName, instanceId, null);
    }

    public UsbEvent(LocalDateTime occurredAt, LocalDateTime receivedAt, String pcName, String userName,
                    String action, String kind, String deviceName, String instanceId, String port) {
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.pcName = pcName;
        this.userName = userName;
        this.action = action;
        this.kind = kind;
        this.deviceName = deviceName;
        this.instanceId = instanceId;
        this.port = port;
    }

    public boolean isBlocked() {
        return action != null && action.startsWith("차단");
    }

    public boolean isAllowed() {
        return action != null && action.startsWith("허용");
    }

    /** 키보드·마우스 지정 포트 기록 */
    public boolean isInputDevice() {
        return INPUT_KINDS.contains(kind) && !isUsbDevice();
    }

    /** 모든 USB 장치의 연결/분리 기록 (2.3.0 이상) */
    public boolean isUsbDevice() {
        return action != null && action.startsWith("USB ");
    }

    public boolean isFileExport() {
        return FILE_EXPORT.equals(action) || FILE_EXPORT_MISSED.equals(action);
    }

    /** 빨간색으로 보여줄 기록: 차단, 키보드·마우스 빠짐/바뀜/지정 외 포트, 반출 기록 누락, 개인정보가 든 파일 반출 */
    public boolean isAlert() {
        return isBlocked() || (isInputDevice() && INPUT_ALERTS.contains(action)) || FILE_EXPORT_MISSED.equals(action)
                || piDetected;
    }

    /** 반출한 파일의 개인정보 검사 결과. counts 가 없으면 note 는 검사하지 못한 이유 */
    public UsbEvent withPi(PiCounts counts, String note) {
        if (counts != null) {
            this.piSummary = counts.summary();
            this.piDetected = counts.isSignificant();
        } else if (note != null && !note.isBlank()) {
            this.piSummary = "검사 못 함: " + (note.length() > 200 ? note.substring(0, 200) : note);
        }
        return this;
    }

    public UsbEvent withFile(String fileName, Long fileSize) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        return this;
    }

    public UsbEvent withPrivacyPc(boolean privacyPc) {
        this.privacyPc = privacyPc;
        return this;
    }

    /** 사람이 읽는 파일 크기 (예: 1.2 MB) */
    public String getFileSizeText() {
        if (fileSize == null) return "";
        if (fileSize < 1024) return fileSize + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double size = fileSize;
        int unit = -1;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format("%.1f %s", size, units[unit]);
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
    public String getPort() { return port; }
    public String getFileName() { return fileName; }
    public Long getFileSize() { return fileSize; }
    public boolean isPrivacyPc() { return privacyPc; }
    public String getPiSummary() { return piSummary; }
    public boolean isPiDetected() { return piDetected; }
}
