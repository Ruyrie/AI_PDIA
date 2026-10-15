package com.heban.pestai.server.client;

import java.util.List;
import java.util.function.Supplier;

/**
 * 问答客户端的路由：每次调用时按当前 DEEPSEEK_API_KEY（环境变量或后台配置）选择 DeepSeek 或 MockChatClient，
 * 运行中添加 / 删除 Key 立即生效，不用重启。
 */
public class RoutingChatClient implements ChatClient {

    private final Supplier<String> apiKey;
    private final ChatClient real;
    private final ChatClient mock;

    public RoutingChatClient(Supplier<String> apiKey, ChatClient real, ChatClient mock) {
        this.apiKey = apiKey;
        this.real = real;
        this.mock = mock;
    }

    /** 本次调用使用的实现 */
    public ChatClient current() {
        String key = apiKey.get();
        return key == null || key.isBlank() ? mock : real;
    }

    @Override
    public void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        current().stream(messages, callback, control);
    }

    @Override
    public boolean isMock() {
        return current().isMock();
    }

    @Override
    public boolean supportsImages() {
        return current().supportsImages();
    }

    @Override
    public String modelName() {
        return current().modelName();
    }
}
