package com.heban.pestai.server.client;

import java.util.List;

/**
 * 问答模型客户端（DeepSeek，流式）。未配置 DEEPSEEK_API_KEY 时为 MockChatClient。
 */
public interface ChatClient {

    /**
     * 流式对话：阻塞直到上游结束；每收到一个非空片段回调一次。
     * 被 control 取消时安静返回（调用方通过 control.isCancelled() 区分）。
     *
     * @throws ChatClientException 上游 HTTP 错误、读取超时、内容无法解析、流意外结束
     */
    void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control) throws ChatClientException;

    /** 是否为 Mock 实现 */
    boolean isMock();

    /** 模型能否直接接收图片；不能时带图提问由 AiChatService 改交识图模型 */
    default boolean supportsImages() {
        return false;
    }

    /** 本次调用将使用的模型名（记录到 t_ai_message.model）；Mock 为 "mock"，未知为 null */
    default String modelName() {
        return null;
    }
}
