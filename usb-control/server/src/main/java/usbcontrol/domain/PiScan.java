package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** PC 한 대의 저장 파일 개인정보 검사 한 번 */
@Entity
@Table(indexes = @Index(columnList = "pcName"))
public class PiScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String pcName;
    /** 정기 / 요청 */
    private String scanTrigger;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime receivedAt;
    private long scannedFiles;
    /** 암호가 걸렸거나 너무 커서 내용을 보지 못한 파일 */
    private long skippedFiles;
    private long detectedFiles;
    /** 검출 파일이 너무 많아 일부만 받음 */
    private boolean truncated;

    protected PiScan() {
    }

    public PiScan(String pcName, String scanTrigger, LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime receivedAt,
                  long scannedFiles, long skippedFiles, long detectedFiles, boolean truncated) {
        this.pcName = pcName;
        this.scanTrigger = scanTrigger;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.receivedAt = receivedAt;
        this.scannedFiles = scannedFiles;
        this.skippedFiles = skippedFiles;
        this.detectedFiles = detectedFiles;
        this.truncated = truncated;
    }

    public Long getId() { return id; }
    public String getPcName() { return pcName; }
    public String getScanTrigger() { return scanTrigger; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public long getScannedFiles() { return scannedFiles; }
    public long getSkippedFiles() { return skippedFiles; }
    public long getDetectedFiles() { return detectedFiles; }
    public boolean isTruncated() { return truncated; }
}
