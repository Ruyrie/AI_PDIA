package com.heban.pestai.server.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import com.heban.pestai.server.config.VisionProperties;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 通义千问识图（默认 qwen3.8-max，原生支持图片输入），DashScope 兼容 Chat Completions 接口；默认关闭深度思考以保证时限。
 * 图片 base64 后以 data 地址放入 image_url 内容；密钥来自环境变量 DASHSCOPE_API_KEY，只在本类内部使用。
 */
public class DashScopeVisionClient implements VisionClient {

    private final VisionProperties properties;
    private final OkHttpClient baseClient;
    private final OkHttpClient streamClient;

    public DashScopeVisionClient(VisionProperties properties, OkHttpClient baseClient) {
        this.properties = properties;
        this.baseClient = baseClient;
        // 带图问答：两次收到数据之间最长 25 秒
        this.streamClient = baseClient.newBuilder()
                .readTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    @Override
    public String recognize(byte[] image, String mimeType, String mode, Duration timeout) throws VisionException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new VisionException(VisionException.Reason.TIMEOUT, "识图时间预算已用完");
        }
        List<ChatMessage> messages = List.of(
                ChatMessage.system(VisionPrompts.systemPrompt(mode)),
                ChatMessage.user(VisionPrompts.USER_INSTRUCTION).withImage(toDataUrl(image, mimeType)));
        String body = OpenAiCompatible.buildRequest(properties.getModel(), messages, false,
                properties.getTemperature(), null, extraParams());
        OkHttpClient client = baseClient.newBuilder()
                .callTimeout(timeout)
                .readTimeout(timeout)
                .build();
        Request request = new Request.Builder()
                .url(properties.getUrl())
                .header("Authorization", "Bearer " + properties.getApiKey())
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
        String body = OpenAiCompatible.buildRequest(properties.getModel(), messages, true, 0.7, null, extraParams());
        OpenAiCompatible.streamChat(streamClient, properties.getUrl(), properties.getApiKey(), body, callback, control);
    }

    private java.util.Map<String, Object> extraParams() {
        return java.util.Map.of("enable_thinking", properties.isEnableThinking());
    }

    @Override
    public boolean isMock() {
        return false;
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
