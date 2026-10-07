package usbcontrol.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import usbcontrol.domain.AuditLog;
import usbcontrol.domain.AuditLogRepository;

import java.time.LocalDateTime;

/** 관리자 작업 이력을 남깁니다. */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** 로그인한 관리자의 작업으로 기록 */
    public void log(String action, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log(auth == null ? "(알 수 없음)" : auth.getName(), action, detail);
    }

    public void log(String adminName, String action, String detail) {
        repository.save(new AuditLog(LocalDateTime.now(), adminName, currentIp(), action, detail));
    }

    public static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return "";
    }
}
