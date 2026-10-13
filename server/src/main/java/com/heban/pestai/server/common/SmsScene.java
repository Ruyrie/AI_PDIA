package com.heban.pestai.server.common;

/**
 * 验证码场景：登录、注册、找回密码、找回账号（验证新手机号）、更换手机号、注销账号、生成账号恢复码。
 * <p>
 * 前四个场景由白名单接口 auth/sms/send 发送；CHANGE_PHONE、DEACTIVATE、RECOVERY 只能由需要登录的
 * user/phone/sms、user/deactivate/sms、user/recovery-code/sms 发送，手机号由服务器决定，
 * 不能借 auth/sms/send 向任意号码发码。
 */
public enum SmsScene {
    LOGIN, REGISTER, RESET, REBIND, CHANGE_PHONE, DEACTIVATE, RECOVERY;

    /** 是否允许通过白名单接口 auth/sms/send 发送 */
    public boolean isPublic() {
        return switch (this) {
            case LOGIN, REGISTER, RESET, REBIND -> true;
            case CHANGE_PHONE, DEACTIVATE, RECOVERY -> false;
        };
    }
}
