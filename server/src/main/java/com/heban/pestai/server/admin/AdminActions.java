package com.heban.pestai.server.admin;

/** 后台操作日志的动作（t_admin_log.action），见 docs/后台管理接口.md「五、操作日志」 */
public final class AdminActions {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String CHANGE_PASSWORD = "CHANGE_PASSWORD";
    public static final String USER_DISABLE = "USER_DISABLE";
    public static final String USER_ENABLE = "USER_ENABLE";
    public static final String USER_LOGOUT = "USER_LOGOUT";
    public static final String USER_PROFILE = "USER_PROFILE";
    public static final String USER_RESET_PASSWORD = "USER_RESET_PASSWORD";
    public static final String USER_DEACTIVATE = "USER_DEACTIVATE";
    public static final String RECOGNITION_DELETE = "RECOGNITION_DELETE";
    public static final String SESSION_DELETE = "SESSION_DELETE";
    public static final String CONFIG_UPDATE = "CONFIG_UPDATE";
    public static final String CONFIG_RESET = "CONFIG_RESET";
    public static final String CONFIG_TEST = "CONFIG_TEST";

    private AdminActions() {
    }
}
