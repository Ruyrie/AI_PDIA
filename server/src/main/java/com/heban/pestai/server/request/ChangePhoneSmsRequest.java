package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.heban.pestai.server.common.Rules;

/** POST user/phone/sms：向新手机号发送更换手机号验证码 */
public class ChangePhoneSmsRequest {

    @NotBlank(message = Rules.PHONE_MESSAGE)
    @Pattern(regexp = Rules.PHONE_REGEX, message = Rules.PHONE_MESSAGE)
    private String newPhone;

    public String getNewPhone() {
        return newPhone;
    }

    public void setNewPhone(String newPhone) {
        this.newPhone = newPhone;
    }
}
