package com.heban.pestai.common.constant;

/**
 * 找回账号时的身份验证方式（auth/recover/verify 的 method）。
 */
public final class RecoverMethod {

    /** 原手机号 + 登录密码 */
    public static final String PASSWORD = "PASSWORD";
    /** 原手机号 + 账号恢复码（换绑时必须同时设置新密码，恢复码使用后失效） */
    public static final String RECOVERY_CODE = "RECOVERY_CODE";

    private RecoverMethod() {
    }
}
