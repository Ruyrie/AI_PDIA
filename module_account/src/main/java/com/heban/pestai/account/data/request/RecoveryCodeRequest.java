package com.heban.pestai.account.data.request;

import androidx.annotation.Nullable;

/**
 * POST user/recovery-code 请求体：JSON 字段 code（发往当前手机号的 RECOVERY 场景验证码）。
 * code 为 null 时 Gson 不序列化该字段，请求体为 {}：10 分钟内刚通过短信验证过手机号时免验证码直接生成
 * （服务器窗口已过期则返回 40001「请先验证当前手机号」）。
 */
public class RecoveryCodeRequest {

    @Nullable
    private final String code;

    public RecoveryCodeRequest(@Nullable String code) {
        this.code = code;
    }

    @Nullable
    public String getCode() {
        return code;
    }
}
