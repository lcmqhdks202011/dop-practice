package usbcontrol.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import usbcontrol.domain.Admin;
import usbcontrol.domain.AdminRepository;
import usbcontrol.domain.Settings;
import usbcontrol.service.AuditService;
import usbcontrol.service.BackupService;
import usbcontrol.service.PasswordRule;
import usbcontrol.service.SettingsService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final SettingsService settings;
    private final AdminRepository admins;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final BackupService backup;

    public SettingsController(SettingsService settings, AdminRepository admins,
                              PasswordEncoder encoder, AuditService audit, BackupService backup) {
        this.settings = settings;
        this.admins = admins;
        this.encoder = encoder;
        this.audit = audit;
        this.backup = backup;
    }

    @GetMapping
    public String show(Model model) throws IOException {
        model.addAttribute("settings", settings.get());
        model.addAttribute("admins", admins.findAllByOrderByUsernameAsc());
        model.addAttribute("backupDir", backup.getBackupDir());
        model.addAttribute("backups", backup.list().stream().limit(5).map(p -> p.getFileName().toString()).toList());
        return "settings";
    }

    @PostMapping("/backup")
    public String backupNow(RedirectAttributes redirect) {
        try {
            Path file = backup.backupNow();
            audit.log("수동 백업", file.getFileName().toString());
            redirect.addFlashAttribute("message", "백업했습니다: " + file);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "백업 실패: " + e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/policy")
    public String updatePolicy(@RequestParam(defaultValue = "false") boolean blockPhones,
                               @RequestParam(defaultValue = "false") boolean notifyUser,
                               @RequestParam(defaultValue = "false") boolean installBlock,
                               RedirectAttributes redirect) {
        Settings before = settings.get();
        String detail = "휴대폰 차단 " + onOff(before.isBlockPhones()) + "→" + onOff(blockPhones)
                + ", 알림창 " + onOff(before.isNotifyUser()) + "→" + onOff(notifyUser)
                + ", 설치 단계 차단 " + onOff(before.isInstallBlock()) + "→" + onOff(installBlock);
        settings.update(blockPhones, notifyUser, installBlock);
        audit.log("차단 설정 변경", detail);
        redirect.addFlashAttribute("message", "저장했습니다. PC에는 30초 안에 반영됩니다.");
        return "redirect:/settings";
    }

    @PostMapping("/agent-key")
    public String regenerateKey(RedirectAttributes redirect) {
        settings.regenerateAgentKey();
        audit.log("접속 키 재발급", "기존 키로 설치된 PC는 새 키로 다시 설치해야 합니다.");
        redirect.addFlashAttribute("message", "접속 키를 새로 만들었습니다. 모든 PC에 새 키로 다시 설치해야 합니다.");
        return "redirect:/settings";
    }

    @PostMapping("/admins")
    @Transactional
    public String addAdmin(@RequestParam String username, @RequestParam String password,
                           @RequestParam String passwordConfirm, RedirectAttributes redirect) {
        String error = LoginController.validateNewAdmin(username, password, passwordConfirm);
        if (error == null && admins.findByUsername(username.trim()).isPresent()) {
            error = "이미 있는 아이디입니다.";
        }
        if (error != null) {
            redirect.addFlashAttribute("error", error);
            return "redirect:/settings";
        }
        admins.save(new Admin(username.trim(), encoder.encode(password), LocalDateTime.now()));
        audit.log("관리자 계정 추가", username.trim());
        redirect.addFlashAttribute("message", "관리자 " + username.trim() + " 을(를) 추가했습니다.");
        return "redirect:/settings";
    }

    @PostMapping("/admins/{id}/delete")
    @Transactional
    public String deleteAdmin(@PathVariable Long id, Authentication auth, RedirectAttributes redirect) {
        Admin admin = admins.findById(id).orElseThrow();
        if (admin.getUsername().equals(auth.getName())) {
            redirect.addFlashAttribute("error", "자기 자신은 지울 수 없습니다.");
            return "redirect:/settings";
        }
        admins.delete(admin);
        audit.log("관리자 계정 삭제", admin.getUsername());
        redirect.addFlashAttribute("message", "관리자 " + admin.getUsername() + " 을(를) 삭제했습니다.");
        return "redirect:/settings";
    }

    @PostMapping("/password")
    @Transactional
    public String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
                                 @RequestParam String newPasswordConfirm, Authentication auth,
                                 RedirectAttributes redirect) {
        Admin me = admins.findByUsername(auth.getName()).orElseThrow();
        String error = null;
        if (!encoder.matches(currentPassword, me.getPasswordHash())) {
            error = "현재 비밀번호가 틀립니다.";
        } else if (!newPassword.equals(newPasswordConfirm)) {
            error = "새 비밀번호 확인이 일치하지 않습니다.";
        } else if (encoder.matches(newPassword, me.getPasswordHash())) {
            error = "지금과 다른 비밀번호를 쓰세요.";
        } else {
            error = PasswordRule.check(newPassword, me.getUsername());
        }
        if (error != null) {
            redirect.addFlashAttribute("error", error);
            return "redirect:/settings";
        }
        me.changePassword(encoder.encode(newPassword), LocalDateTime.now());
        audit.log("비밀번호 변경", "");
        redirect.addFlashAttribute("message", "비밀번호를 바꿨습니다.");
        return "redirect:/settings";
    }

    private static String onOff(boolean b) {
        return b ? "켬" : "끔";
    }
}
