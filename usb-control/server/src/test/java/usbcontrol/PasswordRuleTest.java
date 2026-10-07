package usbcontrol;

import org.junit.jupiter.api.Test;
import usbcontrol.service.PasswordRule;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordRuleTest {

    @Test
    void 세_종류_8자_이상이면_통과() {
        assertThat(PasswordRule.check("Abcdef1!", "admin")).isNull();
        assertThat(PasswordRule.check("abcdef1!", "admin")).isNull();
    }

    @Test
    void 두_종류는_10자_이상이어야_통과() {
        assertThat(PasswordRule.check("abcdefgh12", "admin")).isNull();
        assertThat(PasswordRule.check("abcdefg12", "admin")).isNotNull();
    }

    @Test
    void 짧거나_한_종류면_거절() {
        assertThat(PasswordRule.check("Ab1!", "admin")).isNotNull();
        assertThat(PasswordRule.check("abcdefghijklmn", "admin")).isNotNull();
        assertThat(PasswordRule.check("", "admin")).isNotNull();
    }

    @Test
    void 아이디가_들어가면_거절() {
        assertThat(PasswordRule.check("Admin123!x", "admin")).isNotNull();
    }
}
