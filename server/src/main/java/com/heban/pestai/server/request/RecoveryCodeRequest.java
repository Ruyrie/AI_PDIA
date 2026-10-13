package com.heban.pestai.server.request;

import jakarta.validation.constraints.Pattern;

import com.heban.pestai.server.common.Rules;

/**
 * POST user/recovery-code：当前手机号收到的验证码（场景 RECOVERY；手机号由服务器取当前账号，不由客户端传入）。
 * code 可省略：10 分钟内刚通过短信验证过手机号时可直接生成。
 */
public class RecoveryCodeRequest {

    @Pattern(regexp = Rules.SMS_CODE_REGEX, message = Rules.SMS_CODE_MESSAGE)
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
