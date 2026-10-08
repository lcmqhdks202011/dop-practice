package usbcontrol.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import usbcontrol.domain.*;
import usbcontrol.service.AuditService;
import usbcontrol.service.Csv;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 정기 점검 기록 */
@Controller
@RequestMapping("/reviews")
public class ReviewController {

    public static final List<String> RESULTS = List.of("이상 없음", "조치 필요", "조치 완료");

    private final ReviewRepository reviews;
    private final UsbEventRepository events;
    private final AllowedDeviceRepository devices;
    private final PcRepository pcs;
    private final AuditService audit;

    public ReviewController(ReviewRepository reviews, UsbEventRepository events,
                            AllowedDeviceRepository devices, PcRepository pcs, AuditService audit) {
        this.reviews = reviews;
        this.events = events;
        this.devices = devices;
        this.pcs = pcs;
        this.audit = audit;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("reviews", reviews.findAllByOrderByIdDesc());
        return "reviews";
    }

    /** 점검 화면. 기간을 고르면 그 기간의 차단/허용 건수를 보여줍니다. 기본은 지난달. */
    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          Model model) {
        if (from == null || to == null) {
            LocalDate firstOfThisMonth = LocalDate.now().withDayOfMonth(1);
            from = firstOfThisMonth.minusMonths(1);
            to = firstOfThisMonth.minusDays(1);
        }
        Counts counts = count(from, to);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("counts", counts);
        model.addAttribute("results", RESULTS);
        model.addAttribute("privacyPcs", pcs.findAllByOrderByNameAsc().stream().filter(Pc::isPrivacyPc).toList());
        return "review-form";
    }

    @PostMapping
    @Transactional
    public String create(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam String result, @RequestParam String memo,
                         Authentication auth, RedirectAttributes redirect) {
        if (!RESULTS.contains(result) || memo.isBlank()) {
            redirect.addFlashAttribute("error", "점검 결과와 점검 내용을 입력하세요.");
            return "redirect:/reviews/new?from=" + from + "&to=" + to;
        }
        Counts c = count(from, to);
        Review review = reviews.save(new Review(LocalDateTime.now(), auth.getName(), from, to,
                c.blocked(), c.allowed(), c.activeDevices(), result, memo.trim())
                .withPrivacyCounts(c.privacyBlocked(), c.privacyAllowed(), c.privacyExported()));
        audit.log("정기 점검 기록", "점검번호 " + review.getId() + ", 기간 " + from + " ~ " + to + ", 결과 " + result);
        redirect.addFlashAttribute("message", "점검 기록을 저장했습니다.");
        return "redirect:/reviews";
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        List<List<Object>> rows = reviews.findAllByOrderByIdDesc().stream()
                .map(r -> List.<Object>of(r.getId(), r.getReviewedAt(), r.getReviewer(), r.getPeriodFrom(), r.getPeriodTo(),
                        r.getBlockedCount(), r.getAllowedCount(), r.getActiveDeviceCount(),
                        r.getPrivacyBlockedCount(), r.getPrivacyAllowedCount(), r.getPrivacyExportCount(),
                        r.getResult(), r.getMemo()))
                .toList();
        audit.log("점검 기록 내려받기", rows.size() + "건");
        Csv.write(response, "USB_정기점검기록_" + LocalDate.now() + ".csv",
                List.of("점검번호", "점검일시", "점검자", "기간 시작", "기간 끝", "차단 건수", "허용 건수", "사용 중 매체 수",
                        "개인정보처리 PC 차단", "개인정보처리 PC 허용", "개인정보처리 PC 파일 반출", "결과", "내용"),
                rows);
    }

    /** privacy~: 개인정보처리 PC에서 생긴 기록만 센 건수 */
    public record Counts(long blocked, long allowed, long activeDevices,
                         long privacyBlocked, long privacyAllowed, long privacyExported, long privacyExportMissed) {
    }

    private Counts count(LocalDate from, LocalDate to) {
        List<UsbEvent> list = events.findByOccurredAtBetweenOrderByOccurredAtDesc(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        long blocked = list.stream().filter(UsbEvent::isBlocked).count();
        long allowed = list.stream().filter(UsbEvent::isAllowed).count();
        LocalDate today = LocalDate.now();
        long active = devices.findByRevokedFalse().stream().filter(d -> d.isActive(today)).count();
        List<UsbEvent> privacy = list.stream().filter(UsbEvent::isPrivacyPc).toList();
        return new Counts(blocked, allowed, active,
                privacy.stream().filter(UsbEvent::isBlocked).count(),
                privacy.stream().filter(UsbEvent::isAllowed).count(),
                privacy.stream().filter(e -> UsbEvent.FILE_EXPORT.equals(e.getAction())).count(),
                privacy.stream().filter(e -> UsbEvent.FILE_EXPORT_MISSED.equals(e.getAction())).count());
    }
}
