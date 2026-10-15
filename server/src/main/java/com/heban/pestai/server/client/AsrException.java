package com.heban.pestai.server.client;

/** 语音识别上游失败（HTTP 错误、超时、连接失败、返回内容无法解析），由 AsrService 转为 50201。 */
public class AsrException extends Exception {

    public AsrException(String message) {
        super(message);
    }

    public AsrException(String message, Throwable cause) {
        super(message, cause);
    }
}
