package com.heban.pestai.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问答模型配置 chat.*：DeepSeek（deepseek-chat，流式）。
 */
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    /** 环境变量 DEEPSEEK_API_KEY；为空时使用 MockChatClient */
    private String apiKey = "";
    private String url = "https://api.deepseek.com/chat/completions";
    private String model = "deepseek-flash";
    /** 两次收到数据之间的最长间隔（秒） */
    private int readTimeoutSeconds = 120;
    private double temperature = 0.7;
    /**
     * 单次回答最多生成的 token（思考过程也计入）。默认取 DeepSeek 允许的上限 393216，相当于不限制，
     * 避免长回答或深度思考被截断；小于等于 0 时不传该参数，由平台使用默认值。换模型时注意不要超过该模型的上限。
     */
    private int maxTokens = 393216;
    /** DeepSeek 思考强度 reasoning_effort：none / low / high / max；为空时不传（平台默认 high） */
    private String reasoningEffort = "high";

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }
    /**
     * 问答模型能否直接看图：true 时带图提问也交给问答模型；false（纯文本模型，如 deepseek-v4-pro）时
     * 带图提问自动改用识图模型，避免模型看不到图片却照常作答。
     */
    private boolean imageInput = true;

    public boolean isImageInput() {
        return imageInput;
    }

    public void setImageInput(boolean imageInput) {
        this.imageInput = imageInput;
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

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }
}
