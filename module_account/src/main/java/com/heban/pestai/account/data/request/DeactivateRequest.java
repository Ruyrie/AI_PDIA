package com.heban.pestai.account.data.request;

/** POST user/deactivate 请求体：JSON 字段 code（发往当前手机号的 DEACTIVATE 场景验证码） */
public class DeactivateRequest {

    private final String code;

    public DeactivateRequest(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
