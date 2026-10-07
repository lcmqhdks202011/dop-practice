package usbcontrol.web;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import usbcontrol.domain.Admin;
import usbcontrol.domain.AdminRepository;
import usbcontrol.service.AuditService;
import usbcontrol.service.PasswordRule;

import java.time.LocalDateTime;

@Controller
public class LoginController {

    private final AdminRepository admins;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public LoginController(AdminRepository admins, PasswordEncoder encoder, AuditService audit) {
        this.admins = admins;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping("/login")
    public String login() {
        return admins.count() == 0 ? "redirect:/setup" : "login";
    }

    /** 처음 실행했을 때 첫 관리자 계정 만들기. 관리자가 한 명이라도 있으면 막힙니다. */
    @GetMapping("/setup")
    public String setupForm() {
        return admins.count() == 0 ? "setup" : "redirect:/login";
    }

    @PostMapping("/setup")
    @Transactional
    public String setup(@RequestParam String username, @RequestParam String password,
                        @RequestParam String passwordConfirm, Model model) {
        if (admins.count() > 0) return "redirect:/login";

        String error = validateNewAdmin(username, password, passwordConfirm);
        if (error != null) {
            model.addAttribute("error", error);
            model.addAttribute("username", username);
            return "setup";
        }
        admins.save(new Admin(username.trim(), encoder.encode(password), LocalDateTime.now()));
        audit.log(username.trim(), "첫 관리자 계정 생성", "");
        return "redirect:/login?created";
    }

    static String validateNewAdmin(String username, String password, String passwordConfirm) {
        if (username == null || !username.trim().matches("[A-Za-z0-9._-]{3,30}")) {
            return "아이디는 영문, 숫자, . _ - 로 3~30자입니다.";
        }
        if (!password.equals(passwordConfirm)) {
            return "비밀번호 확인이 일치하지 않습니다.";
        }
        return PasswordRule.check(password, username.trim());
    }
}
