package com.heban.pestai.server.common;

/** 失败原因码（t_recognition.fail_reason）；NETWORK 只在 App 本地产生，不入库。 */
public final class FailReason {
    public static final String TIMEOUT = "TIMEOUT";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String IMAGE_BAD = "IMAGE_BAD";

    private FailReason() {
    }
}
