package com.heban.pestai.account.ui.profile;

/**
 * A06「编辑资料」模式（M01 我的 → 编辑资料）的一次性事件，由 ProfileSetupActivity 执行。
 * 首次「完善资料」模式不使用它，离开时仍发出 {@link com.heban.pestai.account.ui.login.LoginEvent#GO_RECOVERY_GUIDE}。
 */
public enum ProfileEditEvent {
    /** 保存成功（UserSession 已更新）：提示「已保存」并关闭页面，不进入 A12 */
    SAVED,
    /** 返回时有未保存的修改：弹出「放弃本次修改？」 */
    CONFIRM_DISCARD,
    /** 没有修改或确认放弃：直接关闭页面 */
    CLOSE
}
