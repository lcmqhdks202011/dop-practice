package usbcontrol.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import usbcontrol.domain.AuditLogRepository;
import usbcontrol.service.AuditService;
import usbcontrol.service.Csv;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** 관리자 작업 이력. 화면에서는 고치거나 지울 수 없습니다. */
@Controller
@RequestMapping("/audit")
public class AuditController {

    private final AuditLogRepository logs;
    private final AuditService audit;

    public AuditController(AuditLogRepository logs, AuditService audit) {
        this.logs = logs;
        this.audit = audit;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("logs", logs.findTop1000ByOrderByIdDesc());
        return "audit";
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        List<List<Object>> rows = logs.findAllByOrderByIdDesc().stream()
                .map(l -> List.<Object>of(l.getAt(), nz(l.getAdminName()), nz(l.getIpAddress()), nz(l.getAction()), nz(l.getDetail())))
                .toList();
        audit.log("관리 이력 내려받기", rows.size() + "건");
        Csv.write(response, "USB_관리이력_" + LocalDate.now() + ".csv",
                List.of("시간", "관리자", "접속 IP", "작업", "내용"), rows);
    }

    private static Object nz(Object o) {
        return o == null ? "" : o;
    }
}
