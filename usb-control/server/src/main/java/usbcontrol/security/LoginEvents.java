package usbcontrol.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import usbcontrol.domain.AdminRepository;
import usbcontrol.service.AuditService;

import java.time.LocalDateTime;

/** 로그인 성공/실패를 기록하고, 5번 연속 틀리면 10분 동안 계정을 잠급니다. */
@Component
public class LoginEvents {

    public static final int MAX_FAILURES = 5;
    public static final int LOCK_MINUTES = 10;

    private final AdminRepository admins;
    private final AuditService audit;

    public LoginEvents(AdminRepository admins, AuditService audit) {
        this.admins = admins;
        this.audit = audit;
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        String name = event.getAuthentication().getName();
        admins.findByUsername(name).ifPresent(admin -> {
            admin.setFailedCount(0);
            admin.setLockedUntil(null);
            admin.setLastLoginAt(LocalDateTime.now());
        });
        audit.log(name, "로그인", "");
    }

    @EventListener
    @Transactional
    public void onBadPassword(AuthenticationFailureBadCredentialsEvent event) {
        String name = String.valueOf(event.getAuthentication().getPrincipal());
        var found = admins.findByUsername(name);
        if (found.isEmpty()) {
            audit.log(name, "로그인 실패", "없는 아이디");
            return;
        }
        var admin = found.get();
        admin.setFailedCount(admin.getFailedCount() + 1);
        if (admin.getFailedCount() >= MAX_FAILURES) {
            admin.setFailedCount(0);
            admin.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
            audit.log(name, "계정 잠김", MAX_FAILURES + "회 연속 비밀번호 오류로 " + LOCK_MINUTES + "분간 잠금");
        } else {
            audit.log(name, "로그인 실패", "비밀번호 오류 " + admin.getFailedCount() + "회");
        }
    }

    @EventListener
    public void onLocked(AuthenticationFailureLockedEvent event) {
        audit.log(String.valueOf(event.getAuthentication().getPrincipal()), "로그인 실패", "잠긴 계정");
    }
}
