package usbcontrol.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import usbcontrol.domain.Pc;
import usbcontrol.domain.PcRepository;
import usbcontrol.domain.UsbEvent;
import usbcontrol.domain.UsbEventRepository;
import usbcontrol.service.AuditService;
import usbcontrol.service.Csv;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** 전 PC의 USB 사용 기록 */
@Controller
@RequestMapping("/events")
public class EventController {

    private static final int MAX_ROWS_ON_SCREEN = 500;

    private final UsbEventRepository events;
    private final PcRepository pcs;
    private final AuditService audit;

    public EventController(UsbEventRepository events, PcRepository pcs, AuditService audit) {
        this.events = events;
        this.pcs = pcs;
        this.audit = audit;
    }

    @GetMapping
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "") String pc,
                       @RequestParam(defaultValue = "") String type,
                       @RequestParam(defaultValue = "false") boolean privacy,
                       Model model) {
        if (to == null) to = LocalDate.now();
        if (from == null) from = to.minusDays(30);
        List<UsbEvent> found = search(from, to, pc, type, privacy);

        model.addAttribute("events", found.stream().limit(MAX_ROWS_ON_SCREEN).toList());
        model.addAttribute("total", found.size());
        model.addAttribute("maxRows", MAX_ROWS_ON_SCREEN);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("pc", pc);
        model.addAttribute("type", type);
        model.addAttribute("privacy", privacy);
        model.addAttribute("pcNames", pcs.findAllByOrderByNameAsc().stream().map(Pc::getName).toList());
        return "events";
    }

    @GetMapping("/export")
    public void export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "") String pc,
                       @RequestParam(defaultValue = "") String type,
                       @RequestParam(defaultValue = "false") boolean privacy,
                       HttpServletResponse response) throws IOException {
        List<List<Object>> rows = search(from, to, pc, type, privacy).stream()
                .map(e -> List.<Object>of(e.getOccurredAt(), nz(e.getPcName()), e.isPrivacyPc() ? "예" : "",
                        nz(e.getUserName()), nz(e.getAction()), nz(e.getKind()), nz(e.getDeviceName()), nz(e.getPort()),
                        nz(e.getFileName()), nz(e.getFileSize()), nz(e.getInstanceId())))
                .toList();
        audit.log("사용 기록 내려받기", from + " ~ " + to + (privacy ? ", 개인정보처리 PC만" : "") + ", " + rows.size() + "건");
        Csv.write(response, "USB_사용기록_" + from + "_" + to + ".csv",
                List.of("시간", "PC", "개인정보처리 PC", "사용자", "동작", "종류", "매체명", "포트", "파일", "파일 크기(바이트)", "장치ID"),
                rows);
    }

    private List<UsbEvent> search(LocalDate from, LocalDate to, String pc, String type, boolean privacy) {
        return events.findByOccurredAtBetweenOrderByOccurredAtDesc(from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .filter(e -> pc.isBlank() || pc.equalsIgnoreCase(e.getPcName()))
                .filter(e -> !privacy || e.isPrivacyPc())
                .filter(e -> switch (type) {
                    case "차단" -> e.isBlocked();
                    case "허용" -> e.isAllowed();
                    case "입력장치" -> e.isInputDevice();
                    case "반출" -> e.isFileExport();
                    case "기타" -> !e.isBlocked() && !e.isAllowed() && !e.isInputDevice() && !e.isFileExport();
                    default -> true;
                })
                .toList();
    }

    private static Object nz(Object o) {
        return o == null ? "" : o;
    }
}
