package usbcontrol.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import usbcontrol.domain.*;
import usbcontrol.service.PcStatus;
import usbcontrol.service.PolicyService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Controller
public class DashboardController {

    /** 정기 점검 주기 (일) */
    public static final int REVIEW_CYCLE_DAYS = 30;

    private final PcRepository pcs;
    private final UsbEventRepository events;
    private final AllowedDeviceRepository devices;
    private final ReviewRepository reviews;
    private final PolicyService policies;

    public DashboardController(PcRepository pcs, UsbEventRepository events, AllowedDeviceRepository devices,
                               ReviewRepository reviews, PolicyService policies) {
        this.pcs = pcs;
        this.events = events;
        this.devices = devices;
        this.reviews = reviews;
        this.policies = policies;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        List<PcStatus> pcStatuses = pcs.findAllByOrderByNameAsc().stream()
                .map(pc -> PcStatus.of(pc, policies.policyFor(pc.getName()).version(), now))
                .toList();
        List<AllowedDevice> active = devices.findByRevokedFalse().stream().filter(d -> d.isActive(today)).toList();
        List<AllowedDevice> expiringSoon = active.stream()
                .filter(d -> d.getExpiresOn() != null && !d.getExpiresOn().isAfter(today.plusDays(30)))
                .toList();

        var lastReview = reviews.findFirstByOrderByReviewedAtDesc();
        boolean reviewDue = lastReview.map(r -> r.getReviewedAt().isBefore(now.minusDays(REVIEW_CYCLE_DAYS))).orElse(true);

        model.addAttribute("pcStatuses", pcStatuses);
        model.addAttribute("problemPcCount", pcStatuses.stream().filter(s -> !s.level().equals("ok") && !s.level().equals("muted")).count());
        model.addAttribute("activeDeviceCount", active.size());
        model.addAttribute("expiringSoon", expiringSoon);
        model.addAttribute("recentBlocked", events.findTop10ByActionStartingWithOrderByOccurredAtDesc("차단"));
        model.addAttribute("lastReview", lastReview.orElse(null));
        model.addAttribute("reviewDue", reviewDue);
        model.addAttribute("reviewCycleDays", REVIEW_CYCLE_DAYS);
        return "dashboard";
    }
}
