package com.heban.pestai.account.data.request;

/**
 * POST auth/recover/verify 请求体（A09 找回账号第 1 步）：JSON 字段 old_phone、method（PASSWORD / RECOVERY_CODE，见
 * RecoverMethod）、password、recovery_code。只携带所选方式对应的一项，另一项为 null（Gson 不序列化）。
 */
public class RecoverVerifyRequest {

    private final String oldPhone;
    private final String method;
    private final String password;
    private final String recoveryCode;

    public RecoverVerifyRequest(String oldPhone, String method, String password, String recoveryCode) {
        this.oldPhone = oldPhone;
        this.method = method;
        this.password = password;
        this.recoveryCode = recoveryCode;
    }

    public String getOldPhone() {
        return oldPhone;
    }

    public String getMethod() {
        return method;
    }

    public String getPassword() {
        return password;
    }

    public String getRecoveryCode() {
        return recoveryCode;
    }
}
