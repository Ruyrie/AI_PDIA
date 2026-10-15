package com.heban.pestai.server.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.heban.pestai.server.config.ChatProperties;

import okhttp3.OkHttpClient;

/**
 * AI 问答的备用模型：通义千问（CHAT_FALLBACK_MODEL，默认 qwen3.8-max，能看图），DashScope 兼容 Chat Completions 接口，流式。
 * 开启深度思考（enable_thinking: true）：思考内容同样以 delta.reasoning_content 流式返回，App 的思考区照常显示。
 * <p>
 * 密钥为 DASHSCOPE_API_KEY（与识图共用），Key 与模型名每次请求时取一次，后台修改后立即生效。温度、两次收到数据之间的最长间隔
 * 与主模型相同（chat.*）；不传 max_tokens（chat.max-tokens 是 DeepSeek 的上限，对通义千问无效），由平台使用默认值。
 * 只由 FallbackChatClient 在主模型失败时调用。
 */
public class QwenChatClient implements ChatClient {

    private final String url;
    private final double temperature;
    private final Supplier<String> apiKey;
    private final Supplier<String> model;
    private final OkHttpClient client;

    public QwenChatClient(String url, ChatProperties chatProperties, OkHttpClient baseClient,
                          Supplier<String> apiKey, Supplier<String> model) {
        this.url = url;
        this.temperature = chatProperties.getTemperature();
        this.apiKey = apiKey;
        this.model = model;
        this.client = baseClient.newBuilder()
                .readTimeout(Duration.ofSeconds(chatProperties.getReadTimeoutSeconds()))
                .build();
    }

    @Override
    public void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        String body = OpenAiCompatible.buildRequest(model.get(), messages, true, temperature, null,
                Map.of("enable_thinking", true));
        OpenAiCompatible.streamChat(client, url, apiKey.get(), body, callback, control);
    }

    @Override
    public boolean isMock() {
        return false;
    }

    @Override
    public boolean supportsImages() {
        return true;
    }

    @Override
    public String modelName() {
        return model.get();
    }
}
