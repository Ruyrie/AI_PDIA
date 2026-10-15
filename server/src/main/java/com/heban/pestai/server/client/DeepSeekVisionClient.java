package com.heban.pestai.server.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.heban.pestai.server.config.ChatProperties;
import com.heban.pestai.server.config.VisionProperties;

import okhttp3.OkHttpClient;

/**
 * 拍照识别的备用模型：DeepSeek（VISION_FALLBACK_MODEL，默认 deepseek-flash，支持图片输入），chat/completions 非流式。
 * <p>
 * 与通义千问识图完全相同的请求内容：同一份系统提示（按 PEST / CROP）、同一张图片、同一句识图指令（含作物参考信息），
 * 温度取 vision.temperature；返回的文本同样交给 VisionResultParser 解析。关闭思考（reasoning_effort: none，
 * DeepSeek 支持 none / low / high / max），保证在剩余的时间预算内返回。密钥为 DEEPSEEK_API_KEY（与问答共用），
 * Key 与模型名每次请求时取一次。只由 RecognitionService 在主模型失败时调用（见 VisionFallback）；
 * streamChat 为完整实现接口而提供（同一模型的流式问答），带图问答不经过本类。
 */
public class DeepSeekVisionClient implements VisionClient {

    /** 识图备用不需要思考：DeepSeek 的思考强度取 none */
    public static final String REASONING_EFFORT = "none";

    private final String url;
    private final double temperature;
    private final Supplier<String> apiKey;
    private final Supplier<String> model;
    private final OkHttpClient baseClient;
    private final OkHttpClient streamClient;

    public DeepSeekVisionClient(ChatProperties chatProperties, VisionProperties visionProperties,
                                OkHttpClient baseClient, Supplier<String> apiKey, Supplier<String> model) {
        this.url = chatProperties.getUrl();
        this.temperature = visionProperties.getTemperature();
        this.apiKey = apiKey;
        this.model = model;
        this.baseClient = baseClient;
        this.streamClient = baseClient.newBuilder()
                .readTimeout(Duration.ofSeconds(chatProperties.getReadTimeoutSeconds()))
                .build();
    }

    @Override
    public String recognize(byte[] image, String mimeType, String mode, List<String> userCrops, Duration timeout)
            throws VisionException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new VisionException(VisionException.Reason.TIMEOUT, "识图时间预算已用完");
        }
        String body = OpenAiCompatible.buildRequest(model.get(),
                DashScopeVisionClient.recognitionMessages(image, mimeType, mode, userCrops),
                false, temperature, null, Map.of("reasoning_effort", REASONING_EFFORT));
        return DashScopeVisionClient.complete(baseClient, url, apiKey.get(), body, timeout);
    }

    @Override
    public void streamChat(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        String body = OpenAiCompatible.buildRequest(model.get(), messages, true, 0.7, null,
                Map.of("reasoning_effort", REASONING_EFFORT));
        OpenAiCompatible.streamChat(streamClient, url, apiKey.get(), body, callback, control);
    }

    @Override
    public boolean isMock() {
        return false;
    }

    @Override
    public String modelName() {
        return model.get();
    }
}
