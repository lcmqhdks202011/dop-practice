package usbcontrol.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import usbcontrol.domain.*;
import usbcontrol.service.AuditService;
import usbcontrol.service.Csv;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 허용 매체 = 보조저장매체 관리대장 */
@Controller
@RequestMapping("/devices")
public class DeviceController {

    private final AllowedDeviceRepository devices;
    private final UsbEventRepository events;
    private final PcRepository pcs;
    private final AuditService audit;

    public DeviceController(AllowedDeviceRepository devices, UsbEventRepository events,
                            PcRepository pcs, AuditService audit) {
        this.devices = devices;
        this.events = events;
        this.pcs = pcs;
        this.audit = audit;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "사용 중") String status, Model model) {
        List<AllowedDevice> all = devices.findAllByOrderByIdDesc();
        model.addAttribute("devices", status.equals("전체") ? all
                : all.stream().filter(d -> d.getStatus().equals(status)).toList());
        model.addAttribute("status", status);
        model.addAttribute("privacyPcNames", privacyPcNames());
        return "devices";
    }

    /** 새 매체 등록. 차단 기록에서 [허용 등록]을 누르면 eventId 로 내용이 미리 채워집니다. */
    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) Long eventId, Model model) {
        DeviceForm form = new DeviceForm();
        if (eventId != null) {
            events.findById(eventId).ifPresent(e -> {
                form.setInstanceId(e.getInstanceId());
                form.setDeviceName(e.getDeviceName());
                form.setKind(e.getKind());
                form.setOwner(e.getUserName());
            });
        }
        return showForm(form, null, model);
    }

    @PostMapping
    @Transactional
    public String create(@Valid @ModelAttribute("form") DeviceForm form, BindingResult result,
                         Authentication auth, Model model, RedirectAttributes redirect) {
        checkPrivacyPc(form, result);
        if (result.hasErrors()) return showForm(form, null, model);

        AllowedDevice device = new AllowedDevice();
        form.applyTo(device);
        device.setRegisteredAt(LocalDateTime.now());
        device.setRegisteredBy(auth.getName());
        devices.save(device);
        audit.log("허용 매체 등록", "관리번호 " + device.getId() + ": " + form.summary());
        redirect.addFlashAttribute("message", "등록했습니다. PC에는 30초 안에 반영됩니다.");
        return "redirect:/devices";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        AllowedDevice device = devices.findById(id).orElseThrow();
        return showForm(DeviceForm.from(device), device, model);
    }

    @PostMapping("/{id}")
    @Transactional
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") DeviceForm form, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        AllowedDevice device = devices.findById(id).orElseThrow();
        if (device.isRevoked()) return "redirect:/devices";
        checkPrivacyPc(form, result);
        if (result.hasErrors()) return showForm(form, device, model);

        String before = DeviceForm.from(device).summary();
        form.applyTo(device);
        audit.log("허용 매체 수정", "관리번호 " + id + ": [변경 전] " + before + " → [변경 후] " + form.summary());
        redirect.addFlashAttribute("message", "수정했습니다.");
        return "redirect:/devices";
    }

    /** 회수(사용 중지). 기록을 남기기 위해 지우지 않고 '회수' 상태로 둡니다. */
    @PostMapping("/{id}/revoke")
    @Transactional
    public String revoke(@PathVariable Long id, @RequestParam String reason, Authentication auth,
                         RedirectAttributes redirect) {
        AllowedDevice device = devices.findById(id).orElseThrow();
        if (reason == null || reason.isBlank()) {
            redirect.addFlashAttribute("error", "회수 사유를 입력하세요.");
            return "redirect:/devices/" + id + "/edit";
        }
        if (!device.isRevoked()) {
            device.setRevoked(true);
            device.setRevokedAt(LocalDateTime.now());
            device.setRevokedBy(auth.getName());
            device.setRevokedReason(reason.trim());
            audit.log("허용 매체 회수", "관리번호 " + id + " (" + device.getInstanceId() + "), 사유: " + reason.trim());
            redirect.addFlashAttribute("message", "회수했습니다. PC에서는 30초 안에 차단됩니다.");
        }
        return "redirect:/devices";
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        List<String> privacyPcs = privacyPcNames();
        for (AllowedDevice d : devices.findAllByOrderByIdDesc()) {
            boolean privacy = d.getPcName() != null && privacyPcs.stream().anyMatch(n -> n.equalsIgnoreCase(d.getPcName()));
            rows.add(List.of(d.getId(), nz(d.getDeviceName()), nz(d.getKind()), d.getInstanceId(), nz(d.getOwner()),
                    nz(d.getDepartment()), nz(d.getPurpose()), nz(d.getApprover()),
                    d.getPcName() == null ? "전체" : d.getPcName(), privacy ? "예" : "",
                    !privacy ? "" : d.isWriteAllowed() ? "쓰기 허용" : "읽기 전용",
                    d.getRegisteredAt(), nz(d.getRegisteredBy()),
                    d.getExpiresOn() == null ? "" : d.getExpiresOn(), d.getStatus(),
                    d.getRevokedAt() == null ? "" : d.getRevokedAt(), nz(d.getRevokedBy()), nz(d.getRevokedReason())));
        }
        audit.log("관리대장 내려받기", rows.size() + "건");
        Csv.write(response, "보조저장매체_관리대장_" + LocalDate.now() + ".csv",
                List.of("관리번호", "매체명", "종류", "장치ID", "사용자", "부서", "사용 목적", "승인자", "적용 PC",
                        "개인정보처리 PC", "쓰기", "등록일시", "등록 관리자", "만료일", "상태", "회수일시", "회수 관리자", "회수 사유"),
                rows);
    }

    private String showForm(DeviceForm form, AllowedDevice device, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("device", device);
        model.addAttribute("pcNames", pcs.findAllByOrderByNameAsc().stream().map(Pc::getName).toList());
        model.addAttribute("privacyPcNames", privacyPcNames());
        model.addAttribute("maxDays", Pc.PRIVACY_DEVICE_MAX_DAYS);
        return "device-form";
    }

    private List<String> privacyPcNames() {
        return pcs.findAllByOrderByNameAsc().stream().filter(Pc::isPrivacyPc).map(Pc::getName).toList();
    }

    /** 개인정보처리 PC용 매체: 장치 하나씩, 만료일 필수, 사용 기간은 최대 PRIVACY_DEVICE_MAX_DAYS 일 */
    private void checkPrivacyPc(DeviceForm form, BindingResult result) {
        if (form.getPcName() == null || form.getPcName().isBlank()) {
            if (form.isWriteAllowed()) {
                result.rejectValue("writeAllowed", "privacy", "쓰기 허용은 개인정보처리 PC를 적용 PC로 고른 경우에만 씁니다.");
            }
            return;
        }
        boolean privacy = pcs.findByNameIgnoreCase(form.getPcName().trim()).map(Pc::isPrivacyPc).orElse(false);
        if (!privacy) {
            if (form.isWriteAllowed()) {
                result.rejectValue("writeAllowed", "privacy", "일반 PC에서는 원래 쓰기가 됩니다. 개인정보처리 PC에만 체크하세요.");
            }
            return;
        }
        if (form.getInstanceId() != null && form.getInstanceId().contains("*")) {
            result.rejectValue("instanceId", "privacy", "개인정보처리 PC에는 별표(*)로 여러 장치를 한꺼번에 허용할 수 없습니다.");
        }
        LocalDate today = LocalDate.now();
        if (form.getExpiresOn() == null) {
            result.rejectValue("expiresOn", "privacy", "개인정보처리 PC용 매체는 만료일을 꼭 넣어야 합니다.");
        } else if (form.getExpiresOn().isAfter(today.plusDays(Pc.PRIVACY_DEVICE_MAX_DAYS))) {
            result.rejectValue("expiresOn", "privacy", "개인정보처리 PC용 매체는 오늘부터 " + Pc.PRIVACY_DEVICE_MAX_DAYS
                    + "일 안(" + today.plusDays(Pc.PRIVACY_DEVICE_MAX_DAYS) + "까지)으로 넣어야 합니다.");
        }
    }

    private static Object nz(Object o) {
        return o == null ? "" : o;
    }
}
