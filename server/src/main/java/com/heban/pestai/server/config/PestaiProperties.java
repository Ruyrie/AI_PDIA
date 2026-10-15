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

    /**
     * 后台保存的密钥类配置（t_system_config）的加密主密钥，环境变量 PESTAI_CONFIG_SECRET；
     * 为空时由 JWT 签名密钥派生（见 ConfigCipher）
     */
    private String configSecret = "";

    private final Jwt jwt = new Jwt();
    private final Mock mock = new Mock();
    private final Ai ai = new Ai();
    private final Admin admin = new Admin();

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

    public String getConfigSecret() {
        return configSecret;
    }

    public void setConfigSecret(String configSecret) {
        this.configSecret = configSecret;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Admin getAdmin() {
        return admin;
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

    /** 后台管理 */
    public static class Admin {
        /**
         * 初始管理员 admin 的密码（环境变量 PESTAI_ADMIN_INITIAL_PASSWORD）：只在启动时 t_admin 为空、创建 admin 时使用；
         * 为空时随机生成并在启动日志中打印一次。首次登录后必须修改。
         */
        private String initialPassword = "";

        public String getInitialPassword() {
            return initialPassword;
        }

        public void setInitialPassword(String initialPassword) {
            this.initialPassword = initialPassword;
        }
    }

    public static class Ai {
        /** 每个用户 60 秒内最多提问次数（重新生成也计数） */
        private int maxQuestionsPerMinute = 10;
        /** SseEmitter 总时长（秒） */
        private long streamTimeoutSeconds = 300;
        /**
         * 清理未发送的聊天图片与未保存的头像的时间（Spring cron，Asia/Shanghai），默认每天 04:30；设为「-」关闭。
         * 由 ChatImageJanitor 的 @Scheduled 以占位符读取，这里只用于绑定与说明
         */
        private String orphanImageCron = "0 30 4 * * *";

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

        public String getOrphanImageCron() {
            return orphanImageCron;
        }

        public void setOrphanImageCron(String orphanImageCron) {
            this.orphanImageCron = orphanImageCron;
        }
    }
}
