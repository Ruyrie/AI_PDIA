package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.heban.pestai.server.common.Rules;

/** POST user/deactivate：当前手机号收到的验证码（手机号由服务器取当前账号，不由客户端传入） */
public class DeactivateRequest {

    @NotBlank
    @Pattern(regexp = Rules.SMS_CODE_REGEX, message = Rules.SMS_CODE_MESSAGE)
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
