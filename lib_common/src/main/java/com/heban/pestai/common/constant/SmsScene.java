package com.heban.pestai.common.constant;

import androidx.annotation.Nullable;

/**
 * 验证码场景（公共数据 05「四、公共枚举」）。
 */
public final class SmsScene {

    /** 登录 */
    public static final String LOGIN = "LOGIN";
    /** 注册 */
    public static final String REGISTER = "REGISTER";
    /** 找回密码 */
    public static final String RESET = "RESET";
    /** 找回账号：验证码发往新手机号（auth/sms/send，新手机号必须未注册） */
    public static final String REBIND = "REBIND";
    /** 更换手机号：验证码发往新手机号（服务端接口 user/phone/sms，App 不直接用 auth/sms/send 发送） */
    public static final String CHANGE_PHONE = "CHANGE_PHONE";
    /** 注销账号：验证码发往当前手机号（服务端接口 user/deactivate/sms） */
    public static final String DEACTIVATE = "DEACTIVATE";
    /** 生成账号恢复码：验证码发往当前手机号（服务端接口 user/recovery-code/sms） */
    public static final String RECOVERY = "RECOVERY";

    private SmsScene() {
    }
}
