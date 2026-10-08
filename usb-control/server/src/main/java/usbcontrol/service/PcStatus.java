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
    /** 개인정보처리 PC의 읽기 전용과 파일 반출 기록을 지원하는 PC 프로그램 버전 */
    public static final String PRIVACY_AGENT_VERSION = "2.2.0";

    public static PcStatus of(Pc pc, String currentVersion, LocalDateTime now) {
        return of(pc, currentVersion, null, now);
    }

    /** portCheck: 키보드·마우스 지정 포트 문제 (없으면 null). 꺼져 있는 PC는 마지막 상태라 보여주지 않습니다. */
    public static PcStatus of(Pc pc, String currentVersion, InputPortService.PortCheck portCheck, LocalDateTime now) {
        LocalDateTime last = pc.getLastSeenAt();
        if (last == null || last.isBefore(now.minus(LOST_AFTER))) {
            return new PcStatus(pc, "7일 넘게 연결 없음 (확인 필요)", "bad", currentVersion);
        }
        if (last.isBefore(now.minus(OFFLINE_AFTER))) {
            return new PcStatus(pc, "꺼져 있음", "muted", currentVersion);
        }
        if (portCheck != null) {
            return new PcStatus(pc, portCheck.problem(), portCheck.level(), currentVersion);
        }
        if (pc.isPrivacyPc() && olderThan(pc.getAgentVersion(), PRIVACY_AGENT_VERSION)) {
            return new PcStatus(pc, "PC 프로그램 업데이트 필요 (읽기 전용·반출 기록 안 됨)", "bad", currentVersion);
        }
        if (!currentVersion.equals(pc.getAppliedPolicyVersion())) {
            return new PcStatus(pc, "최신 정책 적용 대기", "warn", currentVersion);
        }
        return new PcStatus(pc, "정상", "ok", currentVersion);
    }

    /** '2.1.0' 처럼 점으로 나눈 버전 비교. 알 수 없는 버전은 오래된 것으로 봅니다. */
    public static boolean olderThan(String version, String minimum) {
        if (version == null || version.isBlank()) return true;
        String[] a = version.trim().split("\\."), b = minimum.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x, y = i < b.length ? Integer.parseInt(b[i]) : 0;
            try {
                x = i < a.length ? Integer.parseInt(a[i]) : 0;
            } catch (NumberFormatException e) {
                return true;
            }
            if (x != y) return x < y;
        }
        return false;
    }
}
