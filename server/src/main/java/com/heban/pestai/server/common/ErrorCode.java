package com.heban.pestai.server.common;

/**
 * 错误码（公共数据 04「二、统一响应与错误码」；后台管理新增 40302，见 docs/后台管理接口.md）。
 */
public enum ErrorCode {

    OK(0, "ok"),
    PARAM_ERROR(40001, "参数错误"),
    TOKEN_INVALID(40101, "未登录或登录已过期，请重新登录"),
    WRONG_PASSWORD(40102, "账号或密码错误"),
    SMS_CODE_INVALID(40103, "验证码错误或已过期"),
    NOT_REGISTERED(40104, "该手机号未注册"),
    RECOVERY_CODE_INVALID(40105, "恢复码错误或已失效"),
    ACCOUNT_DISABLED(40301, "该账号已停用"),
    /** 后台：CSRF 令牌不符等无权限的请求（与 ACCOUNT_DISABLED 同为 40301，文案不同） */
    FORBIDDEN(40301, "无权限执行该操作，请刷新页面后重试"),
    /** 后台：初始密码未修改时只允许 auth/me、auth/password、auth/logout */
    MUST_CHANGE_PASSWORD(40302, "请先修改初始密码"),
    PHONE_REGISTERED(40901, "该手机号已注册"),
    TOO_FREQUENT(42901, "操作太频繁，请稍后再试"),
    IMAGE_BAD(42201, "照片不清晰，无法识别"),
    SERVER_ERROR(50001, "服务器开小差了，请稍后再试"),
    AI_UNAVAILABLE(50201, "识别服务暂不可用"),
    AI_TIMEOUT(50401, "识别超时，请重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
