package usbcontrol.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 정기 점검 기록 */
@Entity
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime reviewedAt;
    private String reviewer;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private long blockedCount;
    private long allowedCount;
    private long activeDeviceCount;
    /** 개인정보처리 PC만 따로 센 건수 */
    @Column(columnDefinition = "bigint default 0 not null")
    private long privacyBlockedCount;
    @Column(columnDefinition = "bigint default 0 not null")
    private long privacyAllowedCount;
    @Column(columnDefinition = "bigint default 0 not null")
    private long privacyExportCount;
    private String result;
    @Column(length = 4000)
    private String memo;

    protected Review() {
    }

    public Review(LocalDateTime reviewedAt, String reviewer, LocalDate periodFrom, LocalDate periodTo,
                  long blockedCount, long allowedCount, long activeDeviceCount, String result, String memo) {
        this.reviewedAt = reviewedAt;
        this.reviewer = reviewer;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.blockedCount = blockedCount;
        this.allowedCount = allowedCount;
        this.activeDeviceCount = activeDeviceCount;
        this.result = result;
        this.memo = memo;
    }

    public Review withPrivacyCounts(long blocked, long allowed, long exported) {
        this.privacyBlockedCount = blocked;
        this.privacyAllowedCount = allowed;
        this.privacyExportCount = exported;
        return this;
    }

    public Long getId() { return id; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public String getReviewer() { return reviewer; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public long getBlockedCount() { return blockedCount; }
    public long getAllowedCount() { return allowedCount; }
    public long getActiveDeviceCount() { return activeDeviceCount; }
    public long getPrivacyBlockedCount() { return privacyBlockedCount; }
    public long getPrivacyAllowedCount() { return privacyAllowedCount; }
    public long getPrivacyExportCount() { return privacyExportCount; }
    public String getResult() { return result; }
    public String getMemo() { return memo; }
}
