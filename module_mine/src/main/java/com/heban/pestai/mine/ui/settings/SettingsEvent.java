package com.heban.pestai.mine.ui.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * M02 设置页的一次性事件；CACHE_CLEARED 附带提示文字「已清理 128 MB 图片缓存」。
 */
public final class SettingsEvent {

    public enum Type {
        /** A10 更换手机号 */
        GO_CHANGE_PHONE,
        /** A07 找回密码（带入完整手机号） */
        GO_CHANGE_PWD,
        /** A12 账号恢复码 */
        GO_RECOVERY_CODE,
        /** A11 注销账号 */
        GO_DEACTIVATE,
        /** M03 关于 */
        GO_ABOUT,
        /** A03 登录，不清栈（切换账号；游客为「登录 / 注册」） */
        GO_SWITCH_ACCOUNT,
        /** 弹出退出登录确认对话框 */
        SHOW_LOGOUT_DIALOG,
        /** 清理完成，Snackbar 显示 message */
        CACHE_CLEARED,
        /** 已清除登录态，清栈进入 A03 */
        LOGGED_OUT
    }

    @NonNull
    public final Type type;
    @Nullable
    public final String message;

    private SettingsEvent(@NonNull Type type, @Nullable String message) {
        this.type = type;
        this.message = message;
    }

    @NonNull
    public static SettingsEvent of(@NonNull Type type) {
        return new SettingsEvent(type, null);
    }

    @NonNull
    public static SettingsEvent cacheCleared(@NonNull String message) {
        return new SettingsEvent(Type.CACHE_CLEARED, message);
    }
}
