package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.heban.pestai.server.common.Rules;

/** POST auth/login/sms（未注册自动注册） */
public class SmsLoginRequest {

    @NotBlank
    @Pattern(regexp = Rules.PHONE_REGEX, message = Rules.PHONE_MESSAGE)
    private String phone;

    @NotBlank
    @Pattern(regexp = Rules.SMS_CODE_REGEX, message = Rules.SMS_CODE_MESSAGE)
    private String code;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
