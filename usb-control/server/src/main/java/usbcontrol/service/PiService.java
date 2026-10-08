package usbcontrol.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import usbcontrol.domain.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** PC에 저장된 파일의 개인정보 검사 결과 */
@Service
public class PiService {

    /** PC 한 대의 검사 한 번에 받는 검출 파일 수 */
    public static final int MAX_FINDINGS = 5000;
    /** 개인정보 검사를 지원하는 PC 프로그램 버전 */
    public static final String AGENT_VERSION = "2.4.0";

    /** PC 프로그램이 보내는 개인정보 건수. 순서와 뜻은 PiCounts 와 같습니다. */
    public record CountsReport(int rrn, int foreigner, int passport, int driver, int card, int phone, int email, int account) {
        public PiCounts toCounts() {
            return new PiCounts(rrn, foreigner, passport, driver, card, phone, email, account);
        }
    }

    public record FindingReport(String path, Long size, LocalDateTime modifiedAt, CountsReport counts) {
    }

    /** trigger: 정기 / 요청 */
    public record ScanReport(String pcName, String trigger, LocalDateTime startedAt, LocalDateTime finishedAt,
                             long scannedFiles, long skippedFiles, boolean truncated, List<FindingReport> findings) {
    }

    /** 검사 화면의 PC 한 줄 */
    public record PcRow(Pc pc, PiScan scan, long unresolved, boolean requested, boolean outdated) {
    }

    private final PiScanRepository scans;
    private final PiFindingRepository findings;
    private final PcRepository pcs;

    public PiService(PiScanRepository scans, PiFindingRepository findings, PcRepository pcs) {
        this.scans = scans;
        this.findings = findings;
        this.pcs = pcs;
    }

    @Transactional
    public PiScan save(String pcName, ScanReport report, LocalDateTime now) {
        PiScan previous = latestScans().get(pcName.toLowerCase(Locale.ROOT));
        List<FindingReport> items = report.findings() == null ? List.of() : report.findings().stream()
                .filter(f -> f != null && f.path() != null && !f.path().isBlank() && f.counts() != null)
                .limit(MAX_FINDINGS)
                .toList();
        boolean truncated = report.truncated() || (report.findings() != null && report.findings().size() > MAX_FINDINGS);
        PiScan scan = scans.save(new PiScan(pcName, cut("요청".equals(report.trigger()) ? "요청" : "정기", 20),
                report.startedAt() == null ? now : report.startedAt(), report.finishedAt() == null ? now : report.finishedAt(),
                now, Math.max(0, report.scannedFiles()), Math.max(0, report.skippedFiles()), items.size(), truncated));

        // '업무상 보관', '개인정보 아님'으로 정리한 파일은 다음 검사에서도 그대로 둡니다.
        Map<String, PiFinding> kept = previous == null ? Map.of() : findings.findByScanIdOrderByIdAsc(previous.getId()).stream()
                .filter(f -> f.getActionResult() != null && PiFinding.KEPT_ACTIONS.contains(f.getActionResult()))
                .collect(Collectors.toMap(f -> f.getPath().toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        List<PiFinding> rows = new ArrayList<>();
        for (FindingReport f : items) {
            PiFinding row = new PiFinding(scan.getId(), pcName, cut(f.path(), 1000), f.size(), f.modifiedAt(), f.counts().toCounts());
            PiFinding before = kept.get(row.getPath().toLowerCase(Locale.ROOT));
            if (before != null) row.act(before.getActionResult(), before.getActionMemo(), before.getActionBy(), before.getActionAt());
            rows.add(row);
        }
        findings.saveAll(rows);
        return scan;
    }

    /** PC 이름(소문자) → 가장 최근 검사 */
    public Map<String, PiScan> latestScans() {
        Map<String, PiScan> latest = new LinkedHashMap<>();
        for (PiScan s : scans.findAllByOrderByFinishedAtDescIdDesc()) {
            latest.putIfAbsent(s.getPcName().toLowerCase(Locale.ROOT), s);
        }
        return latest;
    }

    /** 각 PC의 가장 최근 검사에서 나온 파일 */
    public List<PiFinding> latestFindings() {
        List<Long> ids = latestScans().values().stream().map(PiScan::getId).toList();
        return ids.isEmpty() ? List.of() : findings.findByScanIdInOrderByPcNameAscIdAsc(ids);
    }

    public long unresolvedCount() {
        return latestFindings().stream().filter(f -> !f.isResolved()).count();
    }

    public List<PcRow> pcRows() {
        Map<String, PiScan> latest = latestScans();
        Map<Long, Long> unresolved = latestFindings().stream().filter(f -> !f.isResolved())
                .collect(Collectors.groupingBy(PiFinding::getScanId, Collectors.counting()));
        return pcs.findAllByOrderByNameAsc().stream().map(pc -> {
            PiScan scan = latest.get(pc.getName().toLowerCase(Locale.ROOT));
            boolean requested = pc.getPiScanRequestedAt() != null
                    && (scan == null || scan.getStartedAt().isBefore(pc.getPiScanRequestedAt()));
            return new PcRow(pc, scan, scan == null ? 0 : unresolved.getOrDefault(scan.getId(), 0L), requested,
                    PcStatus.olderThan(pc.getAgentVersion(), AGENT_VERSION));
        }).toList();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
