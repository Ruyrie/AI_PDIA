package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.heban.pestai.server.common.Rules;

/** PUT user/phone：新手机号 + 新手机号收到的验证码 */
public class ChangePhoneRequest {

    @NotBlank(message = Rules.PHONE_MESSAGE)
    @Pattern(regexp = Rules.PHONE_REGEX, message = Rules.PHONE_MESSAGE)
    private String newPhone;

    @NotBlank
    @Pattern(regexp = Rules.SMS_CODE_REGEX, message = Rules.SMS_CODE_MESSAGE)
    private String code;

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
}
