package com.heban.pestai.server.client;

/**
 * 识图模型调用失败。reason 为 TIMEOUT（超过时限）或 UNAVAILABLE（连接失败、HTTP 非 2xx、密钥无效、限流等）。
 */
public class VisionException extends Exception {

    public enum Reason {
        TIMEOUT, UNAVAILABLE
    }

    private final Reason reason;

    public VisionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public VisionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
