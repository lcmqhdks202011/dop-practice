package usbcontrol.service;

import usbcontrol.domain.Pc;

import java.time.Duration;
import java.time.LocalDateTime;

/** 적용 현황 화면에 보여줄 PC 상태 */
public record PcStatus(Pc pc, String status, String level, String currentVersion) {

    /** PC 프로그램은 30초마다 서버에 접속합니다. 5분 넘게 소식이 없으면 꺼진 것으로 봅니다. */
    public static final Duration OFFLINE_AFTER = Duration.ofMinutes(5);
    /** 7일 넘게 소식이 없으면 프로그램이 지워졌거나 고장 났을 수 있어 확인이 필요합니다. */
    public static final Duration LOST_AFTER = Duration.ofDays(7);

    public static PcStatus of(Pc pc, String currentVersion, LocalDateTime now) {
        LocalDateTime last = pc.getLastSeenAt();
        if (last == null || last.isBefore(now.minus(LOST_AFTER))) {
            return new PcStatus(pc, "7일 넘게 연결 없음 (확인 필요)", "bad", currentVersion);
        }
        if (last.isBefore(now.minus(OFFLINE_AFTER))) {
            return new PcStatus(pc, "꺼져 있음", "muted", currentVersion);
        }
        if (!currentVersion.equals(pc.getAppliedPolicyVersion())) {
            return new PcStatus(pc, "최신 정책 적용 대기", "warn", currentVersion);
        }
        return new PcStatus(pc, "정상", "ok", currentVersion);
    }
}
