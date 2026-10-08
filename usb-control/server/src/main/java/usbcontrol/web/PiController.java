package usbcontrol.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import usbcontrol.domain.*;
import usbcontrol.service.AuditService;
import usbcontrol.service.Csv;
import usbcontrol.service.PiService;
import usbcontrol.service.SettingsService;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** PC에 저장된 파일의 개인정보 검출 결과와 조치 */
@Controller
@RequestMapping("/pi")
public class PiController {

    private final PiService pi;
    private final PiScanRepository scans;
    private final PiFindingRepository findings;
    private final PcRepository pcs;
    private final SettingsService settings;
    private final AuditService audit;

    public PiController(PiService pi, PiScanRepository scans, PiFindingRepository findings, PcRepository pcs,
                        SettingsService settings, AuditService audit) {
        this.pi = pi;
        this.scans = scans;
        this.findings = findings;
        this.pcs = pcs;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("rows", pi.pcRows());
        model.addAttribute("piScanDays", settings.get().getPiScanDays());
        model.addAttribute("unresolved", pi.unresolvedCount());
        model.addAttribute("agentVersion", PiService.AGENT_VERSION);
        model.addAttribute("threshold", PiCounts.CONTACT_THRESHOLD);
        return "pi";
    }

    @GetMapping("/scans/{id}")
    public String scan(@PathVariable Long id, Model model) {
        PiScan scan = scans.findById(id).orElseThrow();
        model.addAttribute("scan", scan);
        model.addAttribute("findings", findings.findByScanIdOrderByIdAsc(id));
        model.addAttribute("actions", PiFinding.ACTIONS);
        model.addAttribute("latest", pi.latestScans().values().stream().anyMatch(s -> s.getId().equals(id)));
        model.addAttribute("pc", pcs.findByNameIgnoreCase(scan.getPcName()).orElse(null));
        return "pi-scan";
    }

    /** [지금 검사]: PC 프로그램이 다음 연결(30초 안) 때 검사를 시작합니다. */
    @PostMapping("/pcs/{id}/request")
    @Transactional
    public String request(@PathVariable Long id, RedirectAttributes redirect) {
        Pc pc = pcs.findById(id).orElseThrow();
        pc.setPiScanRequestedAt(LocalDateTime.now());
        audit.log("개인정보 검사 요청", pc.getName());
        redirect.addFlashAttribute("message", pc.getName() + " 에 검사를 요청했습니다. 켜져 있으면 30초 안에 시작합니다.");
        return "redirect:/pi";
    }

    @PostMapping("/request-all")
    @Transactional
    public String requestAll(RedirectAttributes redirect) {
        LocalDateTime now = LocalDateTime.now();
        List<Pc> all = pcs.findAllByOrderByNameAsc();
        all.forEach(pc -> pc.setPiScanRequestedAt(now));
        audit.log("개인정보 검사 요청", "모든 PC (" + all.size() + "대)");
        redirect.addFlashAttribute("message", "모든 PC에 검사를 요청했습니다. 켜져 있는 PC는 30초 안에 시작합니다.");
        return "redirect:/pi";
    }

    /** 검출 파일 하나의 조치 결과 */
    @PostMapping("/findings/{id}/action")
    @Transactional
    public String act(@PathVariable Long id, @RequestParam(defaultValue = "") String result,
                      @RequestParam(defaultValue = "") String memo, Authentication auth, RedirectAttributes redirect) {
        PiFinding finding = findings.findById(id).orElseThrow();
        if (!result.isEmpty() && !PiFinding.ACTIONS.contains(result)) {
            redirect.addFlashAttribute("error", "조치 결과를 고르세요.");
            return "redirect:/pi/scans/" + finding.getScanId();
        }
        String note = memo.trim().length() > 1000 ? memo.trim().substring(0, 1000) : memo.trim();
        if (result.isEmpty()) {
            finding.act(null, null, null, null);
        } else {
            finding.act(result, note, auth.getName(), LocalDateTime.now());
        }
        audit.log("개인정보 파일 조치", finding.getPcName() + " / " + finding.getPath() + " / "
                + (result.isEmpty() ? "미조치로 되돌림" : result + (note.isEmpty() ? "" : " (" + note + ")")));
        redirect.addFlashAttribute("message", "조치 결과를 저장했습니다.");
        return "redirect:/pi/scans/" + finding.getScanId();
    }

    /** 각 PC의 가장 최근 검사에서 나온 파일 (개인정보 파일 점검 결과) */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        var latest = pi.latestScans();
        List<List<Object>> rows = new ArrayList<>();
        for (PiFinding f : pi.latestFindings()) {
            PiScan scan = latest.get(f.getPcName().toLowerCase(java.util.Locale.ROOT));
            List<Object> row = new ArrayList<>(List.of(f.getPcName(), scan == null ? "" : scan.getFinishedAt(), f.getPath(),
                    nz(f.getFileSize()), nz(f.getModifiedAt())));
            row.addAll(f.getCounts().values());
            row.addAll(List.of(f.getActionResult() == null ? "미조치" : f.getActionResult(), nz(f.getActionMemo()),
                    nz(f.getActionBy()), nz(f.getActionAt())));
            rows.add(row);
        }
        List<String> header = new ArrayList<>(List.of("PC", "검사일시", "파일", "크기(바이트)", "수정일시"));
        header.addAll(PiCounts.NAMES);
        header.addAll(List.of("조치", "조치 내용", "조치 관리자", "조치일시"));
        audit.log("개인정보 검출 결과 내려받기", rows.size() + "건");
        Csv.write(response, "개인정보_검출결과_" + LocalDate.now() + ".csv", header, rows);
    }

    private static Object nz(Object o) {
        return o == null ? "" : o;
    }
}
