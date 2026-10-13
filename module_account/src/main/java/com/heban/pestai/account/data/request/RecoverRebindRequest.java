package com.heban.pestai.account.data.request;

/**
 * POST auth/recover/rebind 请求体（A09 找回账号第 2 步）：JSON 字段 old_phone、rebind_ticket、new_phone、code
 * （发往新手机号的 REBIND 场景验证码）、new_password。
 * 用恢复码验证身份时 new_password 必填（8–20 位、字母 + 数字）；用登录密码验证时为 null（Gson 不序列化，服务器不改密码）。
 */
public class RecoverRebindRequest {

    private final String oldPhone;
    private final String rebindTicket;
    private final String newPhone;
    private final String code;
    private final String newPassword;

    public RecoverRebindRequest(String oldPhone, String rebindTicket, String newPhone, String code,
                                String newPassword) {
        this.oldPhone = oldPhone;
        this.rebindTicket = rebindTicket;
        this.newPhone = newPhone;
        this.code = code;
        this.newPassword = newPassword;
    }

    public String getOldPhone() {
        return oldPhone;
    }

    public String getRebindTicket() {
        return rebindTicket;
    }

    public String getNewPhone() {
        return newPhone;
    }

    public String getCode() {
        return code;
    }

    public String getNewPassword() {
        return newPassword;
    }
}
