package com.heban.pestai.server.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.heban.pestai.server.client.AliyunSmsClient;
import com.heban.pestai.server.client.ChatClient;
import com.heban.pestai.server.client.DashScopeVisionClient;
import com.heban.pestai.server.client.DeepSeekChatClient;
import com.heban.pestai.server.client.LogSmsClient;
import com.heban.pestai.server.client.MockChatClient;
import com.heban.pestai.server.client.MockVisionClient;
import com.heban.pestai.server.client.SmsClient;
import com.heban.pestai.server.client.VisionClient;

import okhttp3.OkHttpClient;

/**
 * 外部服务客户端：Key 未配置时自动切换为 Mock / 日志实现（启动日志写明），便于在 Key 配置前完整联调。
 */
@Configuration
public class ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientConfig.class);

    /** 各客户端共用连接池与线程池，按需 newBuilder() 调整超时 */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .retryOnConnectionFailure(true)
                .build();
    }

    @Bean
    public VisionClient visionClient(VisionProperties properties, PestaiProperties pestai, OkHttpClient okHttpClient) {
        if (isBlank(properties.getApiKey())) {
            log.warn("未配置环境变量 DASHSCOPE_API_KEY：识图与带图问答使用 MockVisionClient（返回示例结果，约 {}% 的照片模拟「照片不清晰」）",
                    pestai.getMock().getImageBadPercent());
            return new MockVisionClient(pestai.getMock());
        }
        log.info("识图使用通义千问：model={}，深度思考 {}，时限 {} 秒", properties.getModel(),
                properties.isEnableThinking() ? "开启" : "关闭", properties.getTimeoutSeconds());
        return new DashScopeVisionClient(properties, okHttpClient);
    }

    @Bean
    public ChatClient chatClient(ChatProperties properties, PestaiProperties pestai, OkHttpClient okHttpClient) {
        if (isBlank(properties.getApiKey())) {
            log.warn("未配置环境变量 DEEPSEEK_API_KEY：AI 问答使用 MockChatClient（逐段推送固定回答）");
            return new MockChatClient(pestai.getMock());
        }
        log.info("AI 问答使用 DeepSeek：model={}，带图提问{}", properties.getModel(),
                properties.isImageInput() ? "由该模型直接看图" : "改用识图模型");
        return new DeepSeekChatClient(properties, okHttpClient);
    }

    @Bean
    public SmsClient smsClient(SmsProperties properties, OkHttpClient okHttpClient, Clock clock) {
        String provider = properties.getProvider() == null ? "auto" : properties.getProvider().trim().toLowerCase(Locale.ROOT);
        boolean complete = properties.getAliyun().isComplete();
        switch (provider) {
            case "log" -> {
                log.info("短信使用 LogSmsClient（sms.provider=log）：验证码只打印到日志");
                return new LogSmsClient();
            }
            case "aliyun" -> {
                if (!complete) {
                    throw new IllegalStateException("sms.provider=aliyun，但 ALIYUN_SMS_ACCESS_KEY_ID / ALIYUN_SMS_ACCESS_KEY_SECRET"
                            + " / ALIYUN_SMS_SIGN_NAME 未配置齐全");
                }
                log.info("短信使用阿里云号码认证服务 · 短信认证（sms.provider=aliyun），签名 {}", properties.getAliyun().getSignName());
                return new AliyunSmsClient(properties.getAliyun(), okHttpClient, clock);
            }
            default -> {
                if (complete) {
                    log.info("短信使用阿里云号码认证服务 · 短信认证（sms.provider=auto），签名 {}", properties.getAliyun().getSignName());
                    return new AliyunSmsClient(properties.getAliyun(), okHttpClient, clock);
                }
                log.warn("阿里云短信环境变量未配置齐全：短信使用 LogSmsClient，验证码只打印到服务器日志");
                return new LogSmsClient();
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
