package com.heban.pestai.server.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

import com.heban.pestai.server.config.VisionProperties;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 通义千问识图（默认 qwen3.8-max，原生支持图片输入），DashScope 兼容 Chat Completions 接口；默认关闭深度思考以保证时限。
 * 图片 base64 后以 data 地址放入 image_url 内容；密钥来自 DASHSCOPE_API_KEY（环境变量或后台配置），只在本类内部使用。
 * Key 与模型名每次请求时取一次（apiKey / model），后台修改后立即生效。
 */
public class DashScopeVisionClient implements VisionClient {

    private final VisionProperties properties;
    private final Supplier<String> apiKey;
    private final Supplier<String> model;
    private final OkHttpClient baseClient;
    private final OkHttpClient streamClient;

    /** Key 与模型名固定取自 properties（单元测试） */
    public DashScopeVisionClient(VisionProperties properties, OkHttpClient baseClient) {
        this(properties, baseClient, properties::getApiKey, properties::getModel);
    }

    public DashScopeVisionClient(VisionProperties properties, OkHttpClient baseClient,
                                 Supplier<String> apiKey, Supplier<String> model) {
        this.properties = properties;
        this.apiKey = apiKey;
        this.model = model;
        this.baseClient = baseClient;
        // 带图问答：两次收到数据之间最长 25 秒
        this.streamClient = baseClient.newBuilder()
                .readTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    @Override
    public String recognize(byte[] image, String mimeType, String mode, List<String> userCrops, Duration timeout)
            throws VisionException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new VisionException(VisionException.Reason.TIMEOUT, "识图时间预算已用完");
        }
        String body = OpenAiCompatible.buildRequest(model.get(), recognitionMessages(image, mimeType, mode, userCrops),
                false, properties.getTemperature(), null, extraParams());
        return complete(baseClient, properties.getUrl(), apiKey.get(), body, timeout);
    }

    /** 识图请求的消息：系统提示（按模式）+ 带图片的识图指令（有作物时附一行参考信息）；DeepSeekVisionClient（识图备用）共用 */
    static List<ChatMessage> recognitionMessages(byte[] image, String mimeType, String mode, List<String> userCrops) {
        return List.of(
                ChatMessage.system(VisionPrompts.systemPrompt(mode)),
                ChatMessage.user(VisionPrompts.userInstruction(userCrops)).withImage(toDataUrl(image, mimeType)));
    }

    /**
     * 发出一次非流式识图请求并取出正文（DeepSeekVisionClient 共用）：总时限 timeout（callTimeout），
     * 超时抛出 TIMEOUT；连接失败、HTTP 非 2xx、没有正文抛出 UNAVAILABLE。
     */
    static String complete(OkHttpClient baseClient, String url, String apiKey, String body, Duration timeout)
            throws VisionException {
        OkHttpClient client = baseClient.newBuilder()
                .callTimeout(timeout)
                .readTimeout(timeout)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body, OpenAiCompatible.JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                throw new VisionException(VisionException.Reason.UNAVAILABLE,
                        "识图接口返回 HTTP " + response.code() + "：" + abbreviate(text));
            }
            String content = OpenAiCompatible.extractContent(text);
            if (content == null) {
                throw new VisionException(VisionException.Reason.UNAVAILABLE, "识图接口未返回内容");
            }
            return content;
        } catch (InterruptedIOException e) {
            // SocketTimeoutException 与 callTimeout 的「timeout」都属于此类
            throw new VisionException(VisionException.Reason.TIMEOUT, "识图超时", e);
        } catch (IOException e) {
            throw new VisionException(VisionException.Reason.UNAVAILABLE, "识图接口连接失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void streamChat(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        String body = OpenAiCompatible.buildRequest(model.get(), messages, true, 0.7, null, extraParams());
        OpenAiCompatible.streamChat(streamClient, properties.getUrl(), apiKey.get(), body, callback, control);
    }

    private java.util.Map<String, Object> extraParams() {
        return java.util.Map.of("enable_thinking", properties.isEnableThinking());
    }

    @Override
    public boolean isMock() {
        return false;
    }

    @Override
    public String modelName() {
        return model.get();
    }

    public static String toDataUrl(byte[] image, String mimeType) {
        String mime = mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType;
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
