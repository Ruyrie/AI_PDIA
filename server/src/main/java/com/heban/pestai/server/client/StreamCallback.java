package com.heban.pestai.server.client;

import java.io.IOException;

/**
 * 流式回答的片段回调。写出失败（客户端已断开）时抛出 IOException，调用方据此停止读取上游。
 */
@FunctionalInterface
public interface StreamCallback {

    void onDelta(String delta) throws IOException;

    /**
     * 收到一个思考片段（上游 delta.reasoning_content，非空；DeepSeek 在正文开始前输出）。
     * AiChatService 以「event: reasoning」推送给 App 并与正文一起保存。
     * 默认实现转调 {@link #onReasoning()}，不关心思考内容的实现保持原有行为。
     */
    default void onReasoning(String delta) throws IOException {
        onReasoning();
    }

    /**
     * 模型正在思考（不需要思考内容的旧回调，保留以兼容已有实现）。
     * 只由 {@link #onReasoning(String)} 的默认实现调用。
     */
    default void onReasoning() throws IOException {
    }

    /**
     * 本次回答将由哪个模型生成（FallbackChatClient 在每次尝试开始前、任何片段之前回调；其他实现不回调）。
     * 改用备用模型时 fallback 为 true，reason 为主模型的失败原因摘要（不含密钥），否则 reason 为 null。
     * AiChatService 据此在 t_ai_message 中记录 model、fallback（App 不感知）。
     */
    default void onModel(String model, boolean fallback, String reason) {
    }
}
