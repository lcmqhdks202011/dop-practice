package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

/** 검사에서 개인정보가 나온 파일 하나와 관리자의 조치 */
@Entity
@Table(indexes = @Index(columnList = "scanId"))
public class PiFinding {

    public static final List<String> ACTIONS = List.of("삭제함", "암호화함", "업무상 보관 (승인)", "개인정보 아님");
    /** 다음 검사에서 같은 파일이 또 나와도 그대로 이어 가는 조치 */
    public static final List<String> KEPT_ACTIONS = List.of("업무상 보관 (승인)", "개인정보 아님");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long scanId;
    private String pcName;
    @Column(length = 1000)
    private String path;
    private Long fileSize;
    private LocalDateTime modifiedAt;
    @Embedded
    private PiCounts counts;

    /** 비어 있으면 미조치 */
    private String actionResult;
    @Column(length = 1000)
    private String actionMemo;
    private String actionBy;
    private LocalDateTime actionAt;

    protected PiFinding() {
    }

    public PiFinding(Long scanId, String pcName, String path, Long fileSize, LocalDateTime modifiedAt, PiCounts counts) {
        this.scanId = scanId;
        this.pcName = pcName;
        this.path = path;
        this.fileSize = fileSize;
        this.modifiedAt = modifiedAt;
        this.counts = counts;
    }

    public void act(String result, String memo, String by, LocalDateTime at) {
        this.actionResult = result;
        this.actionMemo = memo;
        this.actionBy = by;
        this.actionAt = at;
    }

    public boolean isResolved() {
        return actionResult != null && !actionResult.isBlank();
    }

    public Long getId() { return id; }
    public Long getScanId() { return scanId; }
    public String getPcName() { return pcName; }
    public String getPath() { return path; }
    public Long getFileSize() { return fileSize; }
    public LocalDateTime getModifiedAt() { return modifiedAt; }
    public PiCounts getCounts() { return counts; }
    public String getActionResult() { return actionResult; }
    public String getActionMemo() { return actionMemo; }
    public String getActionBy() { return actionBy; }
    public LocalDateTime getActionAt() { return actionAt; }
}
