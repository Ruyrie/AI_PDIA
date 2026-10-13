package com.heban.pestai.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置 pestai.*（application.yml，值来自环境变量）。
 */
@ConfigurationProperties(prefix = "pestai")
public class PestaiProperties {

    /** App 访问服务器的地址（不含 /pestai/v1），图片地址 = publicBaseUrl + /files/ + 相对路径 */
    private String publicBaseUrl = "http://10.0.2.2:8080";

    /** 文件根目录；为空时 Windows 用 ./data/files，其他系统用 /data/pestai/files */
    private String fileRoot = "";

    /** 安装包下载地址前缀；为空时为 publicBaseUrl + /download/ */
    private String downloadBaseUrl = "";

    private final Jwt jwt = new Jwt();
    private final Mock mock = new Mock();
    private final Ai ai = new Ai();

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getFileRoot() {
        return fileRoot;
    }

    public void setFileRoot(String fileRoot) {
        this.fileRoot = fileRoot;
    }

    public String getDownloadBaseUrl() {
        return downloadBaseUrl;
    }

    public void setDownloadBaseUrl(String downloadBaseUrl) {
        this.downloadBaseUrl = downloadBaseUrl;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Mock getMock() {
        return mock;
    }

    public Ai getAi() {
        return ai;
    }

    public static class Jwt {
        /** HS256 密钥，至少 32 字节；为空时使用开发默认值并在启动日志中告警 */
        private String secret = "";
        private int ttlDays = 30;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getTtlDays() {
            return ttlDays;
        }

        public void setTtlDays(int ttlDays) {
            this.ttlDays = ttlDays;
        }
    }

    /** 未配置大模型 Key 时 Mock 实现的行为 */
    public static class Mock {
        /** 模拟识图耗时（毫秒） */
        private long visionDelayMs = 1500;
        /** 模拟「照片不清晰」的比例（0–100，按图片哈希决定，同一张图结果稳定） */
        private int imageBadPercent = 5;
        /** 模拟流式回答每个片段的间隔（毫秒） */
        private long chatChunkDelayMs = 40;

        public long getVisionDelayMs() {
            return visionDelayMs;
        }

        public void setVisionDelayMs(long visionDelayMs) {
            this.visionDelayMs = visionDelayMs;
        }

        public int getImageBadPercent() {
            return imageBadPercent;
        }

        public void setImageBadPercent(int imageBadPercent) {
            this.imageBadPercent = imageBadPercent;
        }

        public long getChatChunkDelayMs() {
            return chatChunkDelayMs;
        }

        public void setChatChunkDelayMs(long chatChunkDelayMs) {
            this.chatChunkDelayMs = chatChunkDelayMs;
        }
    }

    public static class Ai {
        /** 每个用户 60 秒内最多提问次数（重新生成也计数） */
        private int maxQuestionsPerMinute = 10;
        /** SseEmitter 总时长（秒） */
        private long streamTimeoutSeconds = 300;

        public int getMaxQuestionsPerMinute() {
            return maxQuestionsPerMinute;
        }

        public void setMaxQuestionsPerMinute(int maxQuestionsPerMinute) {
            this.maxQuestionsPerMinute = maxQuestionsPerMinute;
        }

        public long getStreamTimeoutSeconds() {
            return streamTimeoutSeconds;
        }

        public void setStreamTimeoutSeconds(long streamTimeoutSeconds) {
            this.streamTimeoutSeconds = streamTimeoutSeconds;
        }
    }
}
