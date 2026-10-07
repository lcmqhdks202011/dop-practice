package usbcontrol.service;

/**
 * 관리자 비밀번호 규칙.
 * 영문 대문자, 영문 소문자, 숫자, 특수문자 중 2종류 이상이면 10자 이상, 3종류 이상이면 8자 이상.
 */
public final class PasswordRule {

    private PasswordRule() {
    }

    /** 규칙에 맞으면 null, 아니면 이유를 돌려줍니다. */
    public static String check(String password, String username) {
        if (password == null || password.isEmpty()) {
            return "비밀번호를 입력하세요.";
        }
        int kinds = 0;
        if (password.chars().anyMatch(c -> c >= 'A' && c <= 'Z')) kinds++;
        if (password.chars().anyMatch(c -> c >= 'a' && c <= 'z')) kinds++;
        if (password.chars().anyMatch(Character::isDigit)) kinds++;
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) kinds++;

        boolean ok = (kinds >= 3 && password.length() >= 8) || (kinds >= 2 && password.length() >= 10);
        if (!ok) {
            return "비밀번호는 영문 대문자/소문자/숫자/특수문자 중 3종류 이상 섞어 8자 이상, 또는 2종류 이상 섞어 10자 이상이어야 합니다.";
        }
        if (username != null && !username.isBlank() && password.toLowerCase().contains(username.toLowerCase())) {
            return "비밀번호에 아이디를 넣을 수 없습니다.";
        }
        return null;
    }
}
