package com.heban.pestai.server.client;

/**
 * 问答模型调用失败：上游非 2xx、读取超时、返回无法解析、流意外结束等。生成阶段统一转为 [ERROR]（50201）。
 */
public class ChatClientException extends Exception {

    public ChatClientException(String message) {
        super(message);
    }

    public ChatClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
