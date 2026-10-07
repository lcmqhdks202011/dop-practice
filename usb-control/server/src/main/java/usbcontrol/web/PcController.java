package usbcontrol.web;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import usbcontrol.domain.Pc;
import usbcontrol.domain.PcRepository;
import usbcontrol.service.AuditService;
import usbcontrol.service.PcStatus;
import usbcontrol.service.PolicyService;

import java.time.LocalDateTime;

/** PC별 적용 현황 */
@Controller
@RequestMapping("/pcs")
public class PcController {

    private final PcRepository pcs;
    private final PolicyService policies;
    private final AuditService audit;

    public PcController(PcRepository pcs, PolicyService policies, AuditService audit) {
        this.pcs = pcs;
        this.policies = policies;
        this.audit = audit;
    }

    @GetMapping
    public String list(Model model) {
        LocalDateTime now = LocalDateTime.now();
        model.addAttribute("pcStatuses", pcs.findAllByOrderByNameAsc().stream()
                .map(pc -> PcStatus.of(pc, policies.policyFor(pc.getName()).version(), now))
                .toList());
        return "pcs";
    }

    /** 폐기한 PC를 목록에서 뺍니다. 사용 기록은 남습니다. */
    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        Pc pc = pcs.findById(id).orElseThrow();
        pcs.delete(pc);
        audit.log("PC 목록에서 제외", pc.getName());
        redirect.addFlashAttribute("message", pc.getName() + " 을(를) 목록에서 뺐습니다.");
        return "redirect:/pcs";
    }
}
