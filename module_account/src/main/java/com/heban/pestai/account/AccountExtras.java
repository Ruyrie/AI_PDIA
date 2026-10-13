package com.heban.pestai.account;

import android.app.Activity;

/**
 * module_account 内部的 Intent 参数键与结果码（只在本组件内使用，不放入 RouterPath）。
 */
public final class AccountExtras {

    /** boolean：登录页是否因登录过期而进入（与壳工程 PestApp.EXTRA_SESSION_EXPIRED 同名） */
    public static final String EXTRA_SESSION_EXPIRED = "session_expired";
    /** boolean：登录页是否因账号刚注销而进入（A11 → A03，显示「账号已注销」） */
    public static final String EXTRA_ACCOUNT_DEACTIVATED = "account_deactivated";
    /** String：A07 → A08 的一次性重置凭证明文 */
    public static final String EXTRA_RESET_TICKET = "reset_ticket";
    /** long：A07 → A08 的凭证截止时刻（SystemClock.elapsedRealtime() 基准，毫秒） */
    public static final String EXTRA_TICKET_DEADLINE = "ticket_deadline";
    /** A08 返回 A07 的结果码：凭证过期，需要重新获取验证码 */
    public static final int RESULT_TICKET_EXPIRED = Activity.RESULT_FIRST_USER + 1;
    /**
     * boolean：A12 账号恢复码以「引导模式」打开（新用户在 A06 完善资料之后）：标题「保存账号恢复码」、
     * 「稍后再说」/ 完成 / 返回键都以清空任务栈方式进入首页
     */
    public static final String EXTRA_FROM_REGISTER = "from_register";

    private AccountExtras() {
    }
}
