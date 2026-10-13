package com.heban.pestai.server.common;

/**
 * t_reset_ticket.purpose：一次性凭证的用途。找回密码与找回账号共用一张表，凭证只能用于签发时的用途。
 */
public final class TicketPurpose {

    /** 找回密码（auth/password/verify 签发，auth/password/reset 使用） */
    public static final String RESET = "RESET";
    /** 找回账号：用登录密码验证身份（auth/recover/verify 签发，auth/recover/rebind 使用） */
    public static final String REBIND_PASSWORD = "REBIND_PASSWORD";
    /** 找回账号：用账号恢复码验证身份 */
    public static final String REBIND_RECOVERY = "REBIND_RECOVERY";

    private TicketPurpose() {
    }
}
