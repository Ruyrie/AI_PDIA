package com.heban.pestai.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 识图模型配置 vision.*：通义千问 VL（DashScope 兼容 Chat Completions 接口）。
 */
@ConfigurationProperties(prefix = "vision")
public class VisionProperties {

    /** RecognitionVO.model_name，界面显示「由 xx 识别」 */
    private String displayName = "通义千问";
    /** 环境变量 DASHSCOPE_API_KEY；为空时使用 MockVisionClient */
    private String apiKey = "";
    private String url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private String model = "qwen3.8-max";
    /** 识图总时限（秒），小于 App 读取超时 30 秒；带图问答时为两次收到数据之间的最长间隔 */
    private int timeoutSeconds = 25;
    /** 解析失败重试时，剩余时间少于该值则不再重试（毫秒） */
    private long minRetryMillis = 3000;
    private double temperature = 0.1;
    /** 是否开启深度思考；识别要在时限内返回，默认关闭 */
    private boolean enableThinking = false;

    public boolean isEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

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

    public long getMinRetryMillis() {
        return minRetryMillis;
    }

    public void setMinRetryMillis(long minRetryMillis) {
        this.minRetryMillis = minRetryMillis;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
