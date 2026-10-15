package com.heban.pestai.server.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型自动备用配置 ai-fallback.*（docs/后台管理接口.md「八、模型自动备用」）：AI 问答主模型 DeepSeek 失败时改用通义千问，
 * 拍照识别主模型通义千问失败时改用 DeepSeek，App 不提示。
 * <p>
 * enabled、chatModel、visionModel 对应环境变量 AI_FALLBACK_ENABLED、CHAT_FALLBACK_MODEL、VISION_FALLBACK_MODEL，后台可覆盖
 * （RuntimeConfigItem，运行时一律通过 RuntimeConfigService 读取，这里只用于绑定与说明）；各时限只能在 application.yml 中调整。
 */
@ConfigurationProperties(prefix = "ai-fallback")
public class FallbackProperties {

    /** true / false；按字符串绑定，写错时不影响启动（运行时由 RuntimeConfigService.isOn 解析，只有 false / 0 / no / off 为关闭） */
    private String enabled = "true";
    private String chatModel = "qwen3.8-max";
    private String visionModel = "deepseek-flash";
    /** 开始请求问答主模型后这么久（秒）没有任何思考 / 正文片段，取消主模型改用备用模型 */
    private int firstChunkTimeoutSeconds = 20;
    /** 熔断时长（秒）：主模型失败后这段时间内的请求直接交给备用模型 */
    private int breakerSeconds = 60;
    /** 拍照识别备用可用时，主模型（含解析失败重试）最多使用的时间（秒）；总时限仍为 vision.timeout-seconds（25 秒） */
    private int visionPrimarySeconds = 15;

    public Duration firstChunkTimeout() {
        return Duration.ofSeconds(firstChunkTimeoutSeconds);
    }

    public Duration breakerDuration() {
        return Duration.ofSeconds(breakerSeconds);
    }

    public Duration visionPrimaryBudget() {
        return Duration.ofSeconds(visionPrimarySeconds);
    }

    public String getEnabled() {
        return enabled;
    }

    public void setEnabled(String enabled) {
        this.enabled = enabled;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public int getFirstChunkTimeoutSeconds() {
        return firstChunkTimeoutSeconds;
    }

    public void setFirstChunkTimeoutSeconds(int firstChunkTimeoutSeconds) {
        this.firstChunkTimeoutSeconds = firstChunkTimeoutSeconds;
    }

    public int getBreakerSeconds() {
        return breakerSeconds;
    }

    public void setBreakerSeconds(int breakerSeconds) {
        this.breakerSeconds = breakerSeconds;
    }

    public int getVisionPrimarySeconds() {
        return visionPrimarySeconds;
    }

    public void setVisionPrimarySeconds(int visionPrimarySeconds) {
        this.visionPrimarySeconds = visionPrimarySeconds;
    }
}
