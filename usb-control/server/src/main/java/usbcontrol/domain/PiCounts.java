package usbcontrol.domain;

import jakarta.persistence.Embeddable;

import java.util.ArrayList;
import java.util.List;

/** 파일 하나에서 찾은 개인정보 건수. 실제 번호는 서버로 보내지 않고 건수만 받습니다. */
@Embeddable
public class PiCounts {

    public static final List<String> NAMES = List.of(
            "주민등록번호", "외국인등록번호", "여권번호", "운전면허번호", "카드번호", "휴대폰번호", "이메일", "계좌번호");
    /** 휴대폰번호·이메일은 업무 문서에도 흔해서 합쳐서 이만큼 이상일 때만 개인정보 파일로 봅니다. */
    public static final int CONTACT_THRESHOLD = 5;

    private int rrn;
    private int foreigner;
    private int passport;
    private int driver;
    private int card;
    private int phone;
    private int email;
    private int account;

    protected PiCounts() {
    }

    public PiCounts(int rrn, int foreigner, int passport, int driver, int card, int phone, int email, int account) {
        this.rrn = Math.max(0, rrn);
        this.foreigner = Math.max(0, foreigner);
        this.passport = Math.max(0, passport);
        this.driver = Math.max(0, driver);
        this.card = Math.max(0, card);
        this.phone = Math.max(0, phone);
        this.email = Math.max(0, email);
        this.account = Math.max(0, account);
    }

    /** NAMES 와 같은 순서 */
    public List<Integer> values() {
        return List.of(rrn, foreigner, passport, driver, card, phone, email, account);
    }

    /** 고유식별정보·카드·계좌가 하나라도 있거나, 휴대폰·이메일이 기준 이상이면 개인정보 파일 */
    public boolean isSignificant() {
        return rrn + foreigner + passport + driver + card + account > 0 || phone + email >= CONTACT_THRESHOLD;
    }

    public boolean isEmpty() {
        return values().stream().allMatch(v -> v == 0);
    }

    /** 예: 주민등록번호 12, 휴대폰번호 30 */
    public String summary() {
        List<String> parts = new ArrayList<>();
        List<Integer> v = values();
        for (int i = 0; i < NAMES.size(); i++) {
            if (v.get(i) > 0) parts.add(NAMES.get(i) + " " + v.get(i));
        }
        return parts.isEmpty() ? "개인정보 없음" : String.join(", ", parts);
    }

    public int getRrn() { return rrn; }
    public int getForeigner() { return foreigner; }
    public int getPassport() { return passport; }
    public int getDriver() { return driver; }
    public int getCard() { return card; }
    public int getPhone() { return phone; }
    public int getEmail() { return email; }
    public int getAccount() { return account; }
}
