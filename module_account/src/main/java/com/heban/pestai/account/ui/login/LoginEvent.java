package com.heban.pestai.account.ui.login;

/**
 * 登录 / 注册成功后的一次性跳转事件（仅前端使用，不参与接口传输）。
 */
public enum LoginEvent {
    /** 进入首页（清空任务栈） */
    GO_MAIN,
    /** 新用户进入 A06 完善资料 */
    GO_PROFILE_SETUP,
    /** A06 完善资料结束（开始使用 / 跳过 / 返回）：以引导模式打开 A12 保存账号恢复码，再由 A12 进入首页 */
    GO_RECOVERY_GUIDE
}
