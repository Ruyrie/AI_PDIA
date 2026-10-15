package com.heban.pestai.server.client;

import java.time.Duration;
import java.util.function.Supplier;

/** 语音识别客户端的路由：每次调用时按当前 DASHSCOPE_API_KEY（环境变量或后台配置）选择通义千问 ASR 或 MockAsrClient。 */
public class RoutingAsrClient implements AsrClient {

    private final Supplier<String> apiKey;
    private final AsrClient real;
    private final AsrClient mock;

    public RoutingAsrClient(Supplier<String> apiKey, AsrClient real, AsrClient mock) {
        this.apiKey = apiKey;
        this.real = real;
        this.mock = mock;
    }

    /** 本次调用使用的实现 */
    public AsrClient current() {
        String key = apiKey.get();
        return key == null || key.isBlank() ? mock : real;
    }

    @Override
    public String transcribe(byte[] wav, Duration timeout) throws AsrException {
        return current().transcribe(wav, timeout);
    }

    @Override
    public boolean isMock() {
        return current().isMock();
    }
}
