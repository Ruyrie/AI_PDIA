package com.heban.pestai.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import retrofit2.HttpException;

/**
 * 业务异常：错误码 + 中文提示（公共数据 04「二、统一响应与错误码」）。
 * RxResult.transform() 会把网络异常、HTTP 异常、业务错误统一转换为 ApiException，
 * ViewModel 只需显示 {@link #getMessage()}。
 */
public class ApiException extends RuntimeException {

    public static final int OK = 0;
    public static final int PARAM_ERROR = 40001;
    public static final int UNAUTHORIZED = 40101;
    public static final int WRONG_PASSWORD = 40102;
    public static final int SMS_CODE_INVALID = 40103;
    public static final int NOT_REGISTERED = 40104;
    public static final int RECOVERY_CODE_INVALID = 40105;
    public static final int ACCOUNT_DISABLED = 40301;
    public static final int PHONE_REGISTERED = 40901;
    public static final int TOO_FREQUENT = 42901;
    public static final int IMAGE_BAD = 42201;
    public static final int SERVER_ERROR = 50001;
    public static final int AI_UNAVAILABLE = 50201;
    public static final int AI_TIMEOUT = 50401;

    /** App 本地错误码：网络不可用（不会由服务器返回） */
    public static final int NETWORK = -1;
    /** App 本地错误码：请求超时（不会由服务器返回） */
    public static final int TIMEOUT = -2;

    private final int code;

    public ApiException(int code, @Nullable String message) {
        super(message == null || message.isEmpty() ? defaultMessage(code) : message);
        this.code = code;
    }

    public ApiException(int code, @Nullable String message, @Nullable Throwable cause) {
        super(message == null || message.isEmpty() ? defaultMessage(code) : message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** 按错误码表生成带默认提示文案的异常（流式接口等无法使用 RxResult.transform() 的场景） */
    @NonNull
    public static ApiException of(int code) {
        return new ApiException(code, defaultMessage(code));
    }

    @NonNull
    public static ApiException of(int code, @Nullable String message) {
        return new ApiException(code, message);
    }

    /** 把任意异常转换为 ApiException */
    @NonNull
    public static ApiException from(@NonNull Throwable t) {
        if (t instanceof ApiException) {
            return (ApiException) t;
        }
        if (t instanceof HttpException) {
            int status = ((HttpException) t).code();
            if (status == 401) {
                return new ApiException(UNAUTHORIZED, null, t);
            }
            if (status == 429) {
                return new ApiException(TOO_FREQUENT, null, t);
            }
            if (status == 413) {
                return new ApiException(PARAM_ERROR, "图片太大，请重新选择", t);
            }
            return new ApiException(SERVER_ERROR, null, t);
        }
        if (t instanceof SocketTimeoutException || t instanceof InterruptedIOException) {
            return new ApiException(TIMEOUT, null, t);
        }
        if (t instanceof UnknownHostException || t instanceof ConnectException || t instanceof IOException) {
            return new ApiException(NETWORK, null, t);
        }
        return new ApiException(SERVER_ERROR, null, t);
    }

    /** 任意异常对应的中文提示文案 */
    @NonNull
    public static String messageOf(@Nullable Throwable t) {
        return t == null ? defaultMessage(SERVER_ERROR) : from(t).getMessage();
    }

    /** 任意异常对应的错误码 */
    public static int codeOf(@Nullable Throwable t) {
        return t == null ? SERVER_ERROR : from(t).getCode();
    }

    @NonNull
    public static String defaultMessage(int code) {
        switch (code) {
            case OK:
                return "ok";
            case PARAM_ERROR:
                return "参数错误";
            case UNAUTHORIZED:
                return "未登录或登录已过期，请重新登录";
            case WRONG_PASSWORD:
                return "账号或密码错误";
            case SMS_CODE_INVALID:
                return "验证码错误或已过期";
            case NOT_REGISTERED:
                return "该手机号未注册";
            case RECOVERY_CODE_INVALID:
                return "恢复码错误或已失效";
            case ACCOUNT_DISABLED:
                return "该账号已停用";
            case PHONE_REGISTERED:
                return "该手机号已注册";
            case TOO_FREQUENT:
                return "操作太频繁，请稍后再试";
            case IMAGE_BAD:
                return "照片不清晰，无法识别";
            case AI_UNAVAILABLE:
                return "识别服务暂不可用";
            case AI_TIMEOUT:
                return "识别超时，请重试";
            case NETWORK:
                return "网络连接失败，请检查网络后重试";
            case TIMEOUT:
                return "网络超时，请重试";
            case SERVER_ERROR:
            default:
                return "服务器开小差了，请稍后再试";
        }
    }
}
