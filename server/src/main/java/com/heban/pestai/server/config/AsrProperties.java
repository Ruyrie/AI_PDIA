package com.heban.pestai.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音识别配置 asr.*：通义千问 ASR（DashScope 兼容 Chat Completions 接口，与识图共用 DASHSCOPE_API_KEY 和请求地址 vision.url）。
 * DASHSCOPE_API_KEY 为空时使用 MockAsrClient（返回固定的一句话）。
 */
@ConfigurationProperties(prefix = "asr")
public class AsrProperties {

    /** 环境变量 ASR_MODEL；后台可覆盖（RuntimeConfigItem.ASR_MODEL） */
    private String model = "qwen3-asr-flash";
    /** 单次识别的总时限（秒），小于 App 读取超时 30 秒 */
    private int timeoutSeconds = 20;
    /** 每个用户 60 秒内最多识别次数 */
    private int maxRequestsPerMinute = 20;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

    public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }
}
