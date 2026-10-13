package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.heban.pestai.server.common.Rules;

/**
 * POST auth/recover/rebind：原手机号 + rebind_ticket + 新手机号与其验证码（场景 REBIND）+ 新密码。
 * new_password 可省略；传了就必须是 8–20 位且同时包含字母和数字。用恢复码验证身份时是否必填由 RecoverService 按凭证判断。
 */
public class RecoverRebindRequest {

    @NotBlank
    @Pattern(regexp = Rules.PHONE_REGEX, message = "原手机号格式不正确")
    private String oldPhone;

    @NotBlank(message = "验证已过期，请重新验证身份")
    @Size(min = 32, max = 32, message = "验证已过期，请重新验证身份")
    private String rebindTicket;

    @NotBlank
    @Pattern(regexp = Rules.PHONE_REGEX, message = "新手机号格式不正确")
    private String newPhone;

    @NotBlank
    @Pattern(regexp = Rules.SMS_CODE_REGEX, message = Rules.SMS_CODE_MESSAGE)
    private String code;

    @Pattern(regexp = Rules.PASSWORD_REGEX, message = Rules.PASSWORD_MESSAGE)
    private String newPassword;

    public String getOldPhone() {
        return oldPhone;
    }

    public void setOldPhone(String oldPhone) {
        this.oldPhone = oldPhone;
    }

    public String getRebindTicket() {
        return rebindTicket;
    }

    public void setRebindTicket(String rebindTicket) {
        this.rebindTicket = rebindTicket;
    }

    public String getNewPhone() {
        return newPhone;
    }

    public void setNewPhone(String newPhone) {
        this.newPhone = newPhone;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
