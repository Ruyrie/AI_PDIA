package com.heban.pestai.server.client;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 识图客户端的路由：每次调用时按当前 DASHSCOPE_API_KEY（环境变量或后台配置）选择真实实现或 Mock ——
 * 运行中在后台填上 Key 立即改用通义千问，删除后立即退回 MockVisionClient，不用重启。
 */
public class RoutingVisionClient implements VisionClient {

    private final Supplier<String> apiKey;
    private final VisionClient real;
    private final VisionClient mock;

    public RoutingVisionClient(Supplier<String> apiKey, VisionClient real, VisionClient mock) {
        this.apiKey = apiKey;
        this.real = real;
        this.mock = mock;
    }

    /** 本次调用使用的实现 */
    public VisionClient current() {
        String key = apiKey.get();
        return key == null || key.isBlank() ? mock : real;
    }

    @Override
    public String recognize(byte[] image, String mimeType, String mode, List<String> userCrops, Duration timeout)
            throws VisionException {
        return current().recognize(image, mimeType, mode, userCrops, timeout);
    }

    @Override
    public void streamChat(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        current().streamChat(messages, callback, control);
    }

    @Override
    public boolean isMock() {
        return current().isMock();
    }

    @Override
    public String modelName() {
        return current().modelName();
    }
}
