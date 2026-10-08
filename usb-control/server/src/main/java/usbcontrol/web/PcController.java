package usbcontrol.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import usbcontrol.domain.AllowedDevice;
import usbcontrol.domain.AllowedDeviceRepository;
import usbcontrol.domain.DesignatedPort;
import usbcontrol.domain.DesignatedPortRepository;
import usbcontrol.domain.Pc;
import usbcontrol.domain.PcRepository;
import usbcontrol.service.AuditService;
import usbcontrol.service.Csv;
import usbcontrol.service.InputPortService;
import usbcontrol.service.InputPortService.InputDevice;
import usbcontrol.service.PcStatus;
import usbcontrol.service.PolicyService;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** PC별 적용 현황, 개인정보처리 PC 지정, 키보드·마우스 지정 포트 */
@Controller
@RequestMapping("/pcs")
public class PcController {

    private final PcRepository pcs;
    private final PolicyService policies;
    private final InputPortService inputPorts;
    private final DesignatedPortRepository designatedPorts;
    private final AllowedDeviceRepository devices;
    private final AuditService audit;

    public PcController(PcRepository pcs, PolicyService policies, InputPortService inputPorts,
                        DesignatedPortRepository designatedPorts, AllowedDeviceRepository devices, AuditService audit) {
        this.pcs = pcs;
        this.policies = policies;
        this.inputPorts = inputPorts;
        this.designatedPorts = designatedPorts;
        this.devices = devices;
        this.audit = audit;
    }

    /** 이 PC에 내려가는 허용 매체 한 줄과 개인정보처리 PC 기준에 맞는지 */
    public record DeviceRow(AllowedDevice device, String problem) {
    }

    @GetMapping
    public String list(Model model) {
        LocalDateTime now = LocalDateTime.now();
        model.addAttribute("pcStatuses", pcs.findAllByOrderByNameAsc().stream()
                .map(pc -> statusOf(pc, now))
                .toList());
        return "pcs";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Pc pc = pcs.findById(id).orElseThrow();
        model.addAttribute("s", statusOf(pc, LocalDateTime.now()));
        model.addAttribute("portViews", inputPorts.portViews(pc));
        model.addAttribute("deviceViews", inputPorts.deviceViews(pc));
        model.addAttribute("reportsInputs", inputPorts.currentDevices(pc) != null);
        model.addAttribute("deviceRows", deviceRows(pc));
        model.addAttribute("sharedDeviceCount", devices.findByRevokedFalse().stream()
                .filter(d -> d.isActive(LocalDate.now()) && (d.getPcName() == null || d.isWildcard()) && d.appliesTo(pc.getName()))
                .count());
        model.addAttribute("maxDays", Pc.PRIVACY_DEVICE_MAX_DAYS);
        return "pc-detail";
    }

    /** 개인정보처리 PC로 지정하거나 해제합니다. */
    @PostMapping("/{id}/privacy")
    @Transactional
    public String setPrivacy(@PathVariable Long id, @RequestParam boolean privacy,
                             @RequestParam(defaultValue = "") String handler,
                             Authentication auth, RedirectAttributes redirect) {
        Pc pc = pcs.findById(id).orElseThrow();
        String name = handler.trim();
        if (privacy && name.isEmpty()) {
            redirect.addFlashAttribute("error", "개인정보처리자(이 PC를 쓰는 사람)를 입력하세요.");
            return "redirect:/pcs/" + id;
        }
        if (privacy) {
            String before = pc.isPrivacyPc() ? pc.getPrivacyHandler() : null;
            pc.setPrivacyPc(true);
            pc.setPrivacyHandler(name.length() > 255 ? name.substring(0, 255) : name);
            pc.setPrivacySetAt(LocalDateTime.now());
            pc.setPrivacySetBy(auth.getName());
            audit.log(before == null ? "개인정보처리 PC 지정" : "개인정보처리 PC 처리자 변경",
                    pc.getName() + " / 처리자: " + (before == null ? "" : before + " → ") + pc.getPrivacyHandler());
            redirect.addFlashAttribute("message", "개인정보처리 PC로 지정했습니다. 30초 안에 PC에 적용됩니다.");
        } else if (pc.isPrivacyPc()) {
            pc.setPrivacyPc(false);
            pc.setPrivacySetAt(LocalDateTime.now());
            pc.setPrivacySetBy(auth.getName());
            audit.log("개인정보처리 PC 해제", pc.getName() + " / 처리자였던 사람: " + pc.getPrivacyHandler());
            redirect.addFlashAttribute("message", "개인정보처리 PC 지정을 해제했습니다. 30초 안에 PC에 적용됩니다.");
        }
        return "redirect:/pcs/" + id;
    }

    /** PC 목록 (개인정보처리 PC 구분 포함) */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        List<List<Object>> rows = new ArrayList<>();
        for (Pc pc : pcs.findAllByOrderByNameAsc()) {
            PcStatus s = statusOf(pc, now);
            rows.add(List.of(pc.getName(), pc.isPrivacyPc() ? "개인정보처리 PC" : "일반",
                    pc.isPrivacyPc() ? nz(pc.getPrivacyHandler()) : "",
                    pc.isPrivacyPc() && pc.getPrivacySetAt() != null ? pc.getPrivacySetAt() : "",
                    pc.isPrivacyPc() ? nz(pc.getPrivacySetBy()) : "",
                    s.status(), nz(pc.getLastSeenAt()), nz(pc.getLastUser()), nz(pc.getIpAddress()), nz(pc.getOs()),
                    nz(pc.getAgentVersion()), pc.getFirstSeenAt()));
        }
        audit.log("PC 목록 내려받기", rows.size() + "대");
        Csv.write(response, "PC_목록_" + LocalDate.now() + ".csv",
                List.of("PC", "구분", "개인정보처리자", "지정일시", "지정 관리자", "상태", "마지막 연결", "사용자", "IP",
                        "윈도우", "프로그램 버전", "처음 연결"),
                rows);
    }

    /** 지금 꽂혀 있는 키보드·마우스 하나의 포트를 지정 포트로 (이미 지정된 포트면 지금 장치로 바꿈) */
    @PostMapping("/{id}/ports")
    @Transactional
    public String designate(@PathVariable Long id, @RequestParam String kind, @RequestParam String port,
                            Authentication auth, RedirectAttributes redirect) {
        Pc pc = pcs.findById(id).orElseThrow();
        List<InputDevice> current = inputPorts.currentDevices(pc);
        InputDevice d = current == null ? null : InputPortService.find(current, kind, port);
        if (d == null) {
            redirect.addFlashAttribute("error", "그 장치가 지금은 꽂혀 있지 않습니다. 화면을 새로 고친 뒤 다시 해 주세요.");
        } else {
            designate(pc, d, auth.getName());
            redirect.addFlashAttribute("message", d.kind() + " 포트를 지정했습니다. 30초 안에 PC에 적용됩니다.");
        }
        return "redirect:/pcs/" + id;
    }

    /** 지금 꽂혀 있는 키보드·마우스의 포트를 모두 지정 포트로 */
    @PostMapping("/{id}/ports/all")
    @Transactional
    public String designateAll(@PathVariable Long id, Authentication auth, RedirectAttributes redirect) {
        Pc pc = pcs.findById(id).orElseThrow();
        List<InputDevice> current = inputPorts.currentDevices(pc);
        if (current == null || current.isEmpty()) {
            redirect.addFlashAttribute("error", "이 PC에서 USB 키보드·마우스를 찾지 못했습니다.");
        } else {
            current.forEach(d -> designate(pc, d, auth.getName()));
            redirect.addFlashAttribute("message", "지금 꽂혀 있는 키보드·마우스 " + current.size()
                    + "개의 포트를 지정했습니다. 30초 안에 PC에 적용됩니다.");
        }
        return "redirect:/pcs/" + id;
    }

    @PostMapping("/{id}/ports/{portId}/delete")
    @Transactional
    public String undesignate(@PathVariable Long id, @PathVariable Long portId, RedirectAttributes redirect) {
        Pc pc = pcs.findById(id).orElseThrow();
        DesignatedPort rule = designatedPorts.findById(portId)
                .filter(r -> r.getPcName().equalsIgnoreCase(pc.getName()))
                .orElseThrow();
        designatedPorts.delete(rule);
        audit.log("키보드·마우스 지정 포트 해제", describe(pc.getName(), rule.getKind(), rule.getPortLabel(), rule.getDeviceName()));
        redirect.addFlashAttribute("message", rule.getKind() + " 지정 포트를 해제했습니다.");
        return "redirect:/pcs/" + id;
    }

    /** 폐기한 PC를 목록에서 뺍니다. 사용 기록은 남습니다. */
    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        Pc pc = pcs.findById(id).orElseThrow();
        designatedPorts.deleteAll(inputPorts.rulesFor(pc.getName()));
        pcs.delete(pc);
        audit.log("PC 목록에서 제외", pc.getName());
        redirect.addFlashAttribute("message", pc.getName() + " 을(를) 목록에서 뺐습니다.");
        return "redirect:/pcs";
    }

    /** 이 PC에 내려가는 매체. 개인정보처리 PC면 사용 기간이 기준보다 긴 매체를 표시합니다. */
    private List<DeviceRow> deviceRows(Pc pc) {
        LocalDate today = LocalDate.now();
        LocalDate limit = today.plusDays(Pc.PRIVACY_DEVICE_MAX_DAYS);
        return devices.findByRevokedFalse().stream()
                .filter(d -> d.isActive(today) && (pc.isPrivacyPc() ? d.appliesToPrivacyPc(pc.getName()) : d.appliesTo(pc.getName())))
                .map(d -> new DeviceRow(d, !pc.isPrivacyPc() ? null
                        : d.getExpiresOn() == null ? "만료일 없음"
                        : d.getExpiresOn().isAfter(limit) ? Pc.PRIVACY_DEVICE_MAX_DAYS + "일 넘게 남음" : null))
                .toList();
    }

    private static Object nz(Object o) {
        return o == null ? "" : o;
    }

    private PcStatus statusOf(Pc pc, LocalDateTime now) {
        return PcStatus.of(pc, policies.policyFor(pc.getName()).version(), inputPorts.check(pc), now);
    }

    private void designate(Pc pc, InputDevice d, String admin) {
        DesignatedPort rule = inputPorts.rulesFor(pc.getName()).stream()
                .filter(r -> r.getKind().equals(d.kind()) && r.getPort().equalsIgnoreCase(d.port()))
                .findFirst()
                .orElseGet(() -> new DesignatedPort(pc.getName(), d.kind(), d.port()));
        rule.setPortLabel(d.portLabel());
        rule.setDeviceId(d.deviceId());
        rule.setDeviceName(d.name());
        rule.setRegisteredAt(LocalDateTime.now());
        rule.setRegisteredBy(admin);
        designatedPorts.save(rule);
        audit.log("키보드·마우스 지정 포트 등록", describe(pc.getName(), d.kind(), d.portLabel(), d.name()));
    }

    private static String describe(String pcName, String kind, String portLabel, String deviceName) {
        return pcName + " / " + kind + " / " + portLabel + " / " + deviceName;
    }
}
