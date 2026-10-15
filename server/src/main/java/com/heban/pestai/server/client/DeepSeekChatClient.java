package com.heban.pestai.server.client;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import com.heban.pestai.server.config.ChatProperties;

import okhttp3.OkHttpClient;

/**
 * DeepSeek 问答（默认 deepseek-flash，即 DeepSeek-V4.1-Flash，支持图片输入；stream=true）。密钥来自 DEEPSEEK_API_KEY
 * （环境变量或后台配置），只在本类内部使用；Key 与模型名每次请求时取一次，后台修改后立即生效。
 */
public class DeepSeekChatClient implements ChatClient {

    private final ChatProperties properties;
    private final Supplier<String> apiKey;
    private final Supplier<String> model;
    private final OkHttpClient client;

    /** Key 与模型名固定取自 properties（单元测试） */
    public DeepSeekChatClient(ChatProperties properties, OkHttpClient baseClient) {
        this(properties, baseClient, properties::getApiKey, properties::getModel);
    }

    public DeepSeekChatClient(ChatProperties properties, OkHttpClient baseClient,
                              Supplier<String> apiKey, Supplier<String> model) {
        this.properties = properties;
        this.apiKey = apiKey;
        this.model = model;
        // 读取超时是两次收到数据之间的间隔（默认 120 秒），流式输出不受总时长限制
        this.client = baseClient.newBuilder()
                .readTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .build();
    }

    @Override
    public void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        Integer maxTokens = properties.getMaxTokens() > 0 ? properties.getMaxTokens() : null;
        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        String effort = properties.getReasoningEffort();
        if (effort != null && !effort.isBlank()) {
            extra.put("reasoning_effort", effort.trim());
        }
        String body = OpenAiCompatible.buildRequest(model.get(), messages, true,
                properties.getTemperature(), maxTokens, extra);
        OpenAiCompatible.streamChat(client, properties.getUrl(), apiKey.get(), body, callback, control);
    }

    @Override
    public boolean isMock() {
        return false;
    }

    @Override
    public boolean supportsImages() {
        return properties.isImageInput();
    }

    @Override
    public String modelName() {
        return model.get();
    }
}
