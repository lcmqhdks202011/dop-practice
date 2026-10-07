package usbcontrol.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;
import usbcontrol.domain.AdminRepository;

import java.time.LocalDateTime;

/** 모든 화면 위쪽에 공통으로 보여줄 내용 */
@ControllerAdvice
public class GlobalModel {

    /** 관리자 비밀번호 변경 주기 (일) */
    public static final int PASSWORD_MAX_AGE_DAYS = 90;

    private final AdminRepository admins;

    public GlobalModel(AdminRepository admins) {
        this.admins = admins;
    }

    @ModelAttribute
    public void addCommon(Authentication auth, Model model) {
        if (auth == null || !auth.isAuthenticated()) return;
        model.addAttribute("currentAdmin", auth.getName());
        admins.findByUsername(auth.getName()).ifPresent(admin -> model.addAttribute("passwordExpired",
                admin.getPasswordChangedAt() != null
                        && admin.getPasswordChangedAt().isBefore(LocalDateTime.now().minusDays(PASSWORD_MAX_AGE_DAYS))));
        model.addAttribute("passwordMaxAgeDays", PASSWORD_MAX_AGE_DAYS);
    }
}
