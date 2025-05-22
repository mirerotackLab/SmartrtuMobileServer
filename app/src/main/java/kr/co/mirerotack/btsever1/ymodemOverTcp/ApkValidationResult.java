package kr.co.mirerotack.btsever1.ymodemOverTcp;

public class ApkValidationResult {
    private boolean isUpdate;
    private String comment;
    private InstallResult installCode;
    private UninstallResult uninstallCode;

    // ✅ 기본 생성자 (false, 빈 문자열로 초기화)
    public ApkValidationResult() {
        this.isUpdate = false;
        this.comment = "";
        this.installCode = InstallResult.NONE;
        this.uninstallCode = UninstallResult.NONE;
    }

    // ✅ 매개변수 있는 생성자 (값을 직접 설정 가능)
    public ApkValidationResult(boolean isUpdate, String comment, InstallResult installCode) {
        this.isUpdate = isUpdate;
        this.comment = comment;
        this.installCode = installCode;
    }

    public ApkValidationResult(boolean isUpdate, String comment, UninstallResult uninstallCode) {
        this.isUpdate = isUpdate;
        this.comment = comment;
        this.uninstallCode = uninstallCode;
    }

    // ✅ Getter & Setter
    public boolean getIsUpdate() { return isUpdate; }
    public String getComment() { return comment; }
    public InstallResult getInstallCode() {  return installCode; }
    public UninstallResult getUninstallCode() { return uninstallCode; }
}