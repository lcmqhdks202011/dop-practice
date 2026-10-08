package usbcontrol.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import usbcontrol.domain.AllowedDevice;

import java.time.LocalDate;

/** 허용 매체 등록/수정 입력 칸 */
public class DeviceForm {

    @NotBlank(message = "장치 ID를 입력하세요.")
    @Size(max = 500)
    private String instanceId;
    @Size(max = 255)
    private String deviceName;
    @Size(max = 255)
    private String kind = "USB저장장치";
    @NotBlank(message = "사용자를 입력하세요.")
    @Size(max = 255)
    private String owner;
    @Size(max = 255)
    private String department;
    @NotBlank(message = "사용 목적을 입력하세요.")
    @Size(max = 255)
    private String purpose;
    @NotBlank(message = "승인자를 입력하세요.")
    @Size(max = 255)
    private String approver;
    @Size(max = 255)
    private String pcName;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiresOn;
    private boolean writeAllowed;

    public static DeviceForm from(AllowedDevice d) {
        DeviceForm f = new DeviceForm();
        f.instanceId = d.getInstanceId();
        f.deviceName = d.getDeviceName();
        f.kind = d.getKind();
        f.owner = d.getOwner();
        f.department = d.getDepartment();
        f.purpose = d.getPurpose();
        f.approver = d.getApprover();
        f.pcName = d.getPcName();
        f.expiresOn = d.getExpiresOn();
        f.writeAllowed = d.isWriteAllowed();
        return f;
    }

    public void applyTo(AllowedDevice d) {
        d.setInstanceId(instanceId.trim());
        d.setDeviceName(trim(deviceName));
        d.setKind(trim(kind));
        d.setOwner(trim(owner));
        d.setDepartment(trim(department));
        d.setPurpose(trim(purpose));
        d.setApprover(trim(approver));
        d.setPcName(trim(pcName));
        d.setExpiresOn(expiresOn);
        d.setWriteAllowed(writeAllowed);
    }

    /** 관리 이력에 남길 요약 */
    public String summary() {
        return "장치ID=" + instanceId + ", 매체명=" + nz(deviceName) + ", 사용자=" + nz(owner) + ", 부서=" + nz(department)
                + ", 목적=" + nz(purpose) + ", 승인자=" + nz(approver)
                + ", 적용PC=" + (pcName == null || pcName.isBlank() ? "전체" : pcName)
                + ", 만료일=" + (expiresOn == null ? "없음" : expiresOn)
                + (writeAllowed ? ", 개인정보처리PC 쓰기 허용" : "");
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getApprover() { return approver; }
    public void setApprover(String approver) { this.approver = approver; }
    public String getPcName() { return pcName; }
    public void setPcName(String pcName) { this.pcName = pcName; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }
    public boolean isWriteAllowed() { return writeAllowed; }
    public void setWriteAllowed(boolean writeAllowed) { this.writeAllowed = writeAllowed; }
}
