package usbcontrol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** 전체 설정. 한 줄(id=1)만 씁니다. */
@Entity
public class Settings {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    /** PC 프로그램이 서버에 접속할 때 쓰는 비밀 키 */
    private String agentKey;
    /** 휴대폰 파일 전송도 막을지 */
    private boolean blockPhones = true;
    /** 차단할 때 사용자 화면에 알림 창을 띄울지 */
    private boolean notifyUser = true;
    /** 윈도우 장치 설치 정책으로도 막을지 (꽂는 순간부터 빈틈 없이 차단) */
    private boolean installBlock;
    /** PC에 저장된 파일의 개인정보 검사 주기 (일). 0이면 정기 검사 안 함 */
    @Column(columnDefinition = "integer default 7 not null")
    private int piScanDays = 7;

    protected Settings() {
    }

    public Settings(String agentKey) {
        this.agentKey = agentKey;
    }

    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public boolean isBlockPhones() { return blockPhones; }
    public void setBlockPhones(boolean blockPhones) { this.blockPhones = blockPhones; }
    public boolean isNotifyUser() { return notifyUser; }
    public void setNotifyUser(boolean notifyUser) { this.notifyUser = notifyUser; }
    public boolean isInstallBlock() { return installBlock; }
    public void setInstallBlock(boolean installBlock) { this.installBlock = installBlock; }
    public int getPiScanDays() { return piScanDays; }
    public void setPiScanDays(int piScanDays) { this.piScanDays = piScanDays; }
}
