package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.heban.pestai.server.common.Rules;

/** POST auth/password/reset */
public class PasswordResetRequest {

    @NotBlank
    @Pattern(regexp = Rules.PHONE_REGEX, message = Rules.PHONE_MESSAGE)
    private String phone;

    @NotBlank(message = "验证已过期，请重新获取验证码")
    @Size(min = 32, max = 32, message = "验证已过期，请重新获取验证码")
    private String resetTicket;

    @NotBlank(message = Rules.PASSWORD_MESSAGE)
    @Pattern(regexp = Rules.PASSWORD_REGEX, message = Rules.PASSWORD_MESSAGE)
    private String newPassword;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getResetTicket() {
        return resetTicket;
    }

    public void setResetTicket(String resetTicket) {
        this.resetTicket = resetTicket;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
