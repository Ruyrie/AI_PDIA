package com.heban.pestai.server.common;

/**
 * 业务异常：携带错误码与提示文案，由 GlobalExceptionHandler 转为 ApiResponse。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message == null || message.isEmpty() ? errorCode.getMessage() : message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }

    public static BizException param(String message) {
        return new BizException(ErrorCode.PARAM_ERROR, message);
    }
}
