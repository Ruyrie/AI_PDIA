package com.heban.pestai.server.config;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.heban.pestai.server.client.AliyunSmsClient;
import com.heban.pestai.server.client.AsrClient;
import com.heban.pestai.server.client.ChatClient;
import com.heban.pestai.server.client.DashScopeAsrClient;
import com.heban.pestai.server.client.DashScopeVisionClient;
import com.heban.pestai.server.client.DeepSeekChatClient;
import com.heban.pestai.server.client.DeepSeekVisionClient;
import com.heban.pestai.server.client.FallbackBreaker;
import com.heban.pestai.server.client.FallbackChatClient;
import com.heban.pestai.server.client.LogSmsClient;
import com.heban.pestai.server.client.MockAsrClient;
import com.heban.pestai.server.client.MockChatClient;
import com.heban.pestai.server.client.MockVisionClient;
import com.heban.pestai.server.client.QwenChatClient;
import com.heban.pestai.server.client.RoutingAsrClient;
import com.heban.pestai.server.client.RoutingChatClient;
import com.heban.pestai.server.client.RoutingSmsClient;
import com.heban.pestai.server.client.RoutingVisionClient;
import com.heban.pestai.server.client.SmsClient;
import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.client.VisionFallback;

import okhttp3.OkHttpClient;

/**
 * 外部服务客户端。Key 与模型名由 RuntimeConfigService 每次请求时提供（后台设置 > 环境变量 / .env > 默认值）；
 * 真实实现与 Mock / 日志实现之间的选择也是每次调用时进行（Routing*Client）：Key 未配置时自动使用 Mock，
 * 运行中在后台填上 Key 立即改用真实服务，删除后立即退回 Mock，不用重启。启动日志写明启动时的状态。
 * <p>
 * 模型自动备用（docs/后台管理接口.md「八」）：AI 问答为 FallbackChatClient（主 DeepSeek → 备通义千问），
 * 拍照识别的备用为 VisionFallback（主通义千问 → 备 DeepSeek，由 RecognitionService 使用）；备用客户端不单独注册为
 * ChatClient / VisionClient Bean，按类型注入的仍是原来的主客户端。
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
    public VisionClient visionClient(VisionProperties properties, PestaiProperties pestai, OkHttpClient okHttpClient,
                                     RuntimeConfigService runtimeConfig) {
        Supplier<String> apiKey = () -> runtimeConfig.get(RuntimeConfigItem.DASHSCOPE_API_KEY);
        Supplier<String> model = () -> runtimeConfig.get(RuntimeConfigItem.VISION_MODEL);
        RoutingVisionClient client = new RoutingVisionClient(apiKey,
                new DashScopeVisionClient(properties, okHttpClient, apiKey, model),
                new MockVisionClient(pestai.getMock()));
        if (client.isMock()) {
            log.warn("未配置 DASHSCOPE_API_KEY（环境变量与后台配置）：识图与带图问答暂用 MockVisionClient（返回示例结果，"
                    + "约 {}% 的照片模拟「照片不清晰」）；在后台填写 Key 后立即改用通义千问", pestai.getMock().getImageBadPercent());
        } else {
            log.info("识图使用通义千问：model={}，深度思考 {}，时限 {} 秒", model.get(),
                    properties.isEnableThinking() ? "开启" : "关闭", properties.getTimeoutSeconds());
        }
        return client;
    }

    /**
     * AI 问答：主模型 DeepSeek（未配置 Key 时为 Mock），在任何片段之前失败（含 20 秒没有片段）时改用通义千问
     * （CHAT_FALLBACK_MODEL，DASHSCOPE_API_KEY，开启深度思考），之后 60 秒内直接使用通义千问。
     */
    @Bean
    public ChatClient chatClient(ChatProperties properties, VisionProperties visionProperties,
                                 FallbackProperties fallbackProperties, PestaiProperties pestai, OkHttpClient okHttpClient,
                                 RuntimeConfigService runtimeConfig, Clock clock) {
        Supplier<String> apiKey = () -> runtimeConfig.get(RuntimeConfigItem.DEEPSEEK_API_KEY);
        Supplier<String> model = () -> runtimeConfig.get(RuntimeConfigItem.CHAT_MODEL);
        RoutingChatClient primary = new RoutingChatClient(apiKey,
                new DeepSeekChatClient(properties, okHttpClient, apiKey, model),
                new MockChatClient(pestai.getMock()));
        Supplier<String> fallbackKey = () -> runtimeConfig.get(RuntimeConfigItem.DASHSCOPE_API_KEY);
        Supplier<String> fallbackModel = () -> runtimeConfig.get(RuntimeConfigItem.CHAT_FALLBACK_MODEL);
        FallbackChatClient client = new FallbackChatClient(primary,
                new QwenChatClient(visionProperties.getUrl(), properties, okHttpClient, fallbackKey, fallbackModel),
                new FallbackBreaker(runtimeConfig::fallbackEnabled, fallbackKey, clock, fallbackProperties.breakerDuration()),
                fallbackProperties.firstChunkTimeout());
        if (client.isMock()) {
            log.warn("未配置 DEEPSEEK_API_KEY（环境变量与后台配置）：AI 问答暂用 MockChatClient（逐段推送固定回答）；"
                    + "在后台填写 Key 后立即改用 DeepSeek");
        } else {
            log.info("AI 问答使用 DeepSeek：model={}，带图提问{}", model.get(),
                    properties.isImageInput() ? "由该模型直接看图" : "改用识图模型");
        }
        return client;
    }

    /**
     * 拍照识别的备用：DeepSeek（VISION_FALLBACK_MODEL，DEEPSEEK_API_KEY，非流式、关闭思考），与通义千问使用相同的提示词与图片。
     * 备用可用时主模型限制在 15 秒内（总时限仍为 25 秒），失败后 60 秒内直接使用 DeepSeek。
     */
    @Bean
    public VisionFallback visionFallback(ChatProperties chatProperties, VisionProperties visionProperties,
                                         FallbackProperties fallbackProperties, OkHttpClient okHttpClient,
                                         RuntimeConfigService runtimeConfig, Clock clock) {
        Supplier<String> apiKey = () -> runtimeConfig.get(RuntimeConfigItem.DEEPSEEK_API_KEY);
        Supplier<String> model = () -> runtimeConfig.get(RuntimeConfigItem.VISION_FALLBACK_MODEL);
        return new VisionFallback(
                new DeepSeekVisionClient(chatProperties, visionProperties, okHttpClient, apiKey, model),
                new FallbackBreaker(runtimeConfig::fallbackEnabled, apiKey, clock, fallbackProperties.breakerDuration()),
                fallbackProperties.visionPrimaryBudget());
    }

    @Bean
    public AsrClient asrClient(VisionProperties visionProperties, OkHttpClient okHttpClient,
                               RuntimeConfigService runtimeConfig) {
        Supplier<String> apiKey = () -> runtimeConfig.get(RuntimeConfigItem.DASHSCOPE_API_KEY);
        Supplier<String> model = () -> runtimeConfig.get(RuntimeConfigItem.ASR_MODEL);
        RoutingAsrClient client = new RoutingAsrClient(apiKey,
                new DashScopeAsrClient(visionProperties.getUrl(), apiKey, model, okHttpClient),
                new MockAsrClient());
        if (client.isMock()) {
            log.warn("未配置 DASHSCOPE_API_KEY：语音识别暂用 MockAsrClient（固定返回「{}」）", MockAsrClient.MOCK_TEXT);
        } else {
            log.info("语音识别使用通义千问：model={}", model.get());
        }
        return client;
    }

    @Bean
    public SmsClient smsClient(SmsProperties properties, OkHttpClient okHttpClient, Clock clock,
                               RuntimeConfigService runtimeConfig) {
        Supplier<SmsProperties.Aliyun> config = () -> runtimeConfig.smsAliyun(properties.getAliyun());
        RoutingSmsClient client = new RoutingSmsClient(properties.getProvider(), config,
                new AliyunSmsClient(config, okHttpClient, clock), new LogSmsClient());
        boolean complete = config.get().isComplete();
        switch (client.provider()) {
            case RoutingSmsClient.PROVIDER_LOG ->
                    log.info("短信使用 LogSmsClient（sms.provider=log）：验证码只打印到日志");
            case RoutingSmsClient.PROVIDER_ALIYUN -> {
                if (!complete) {
                    throw new IllegalStateException("sms.provider=aliyun，但 ALIYUN_SMS_ACCESS_KEY_ID / ALIYUN_SMS_ACCESS_KEY_SECRET"
                            + " / ALIYUN_SMS_SIGN_NAME 未配置齐全");
                }
                log.info("短信使用阿里云号码认证服务 · 短信认证（sms.provider=aliyun），签名 {}", config.get().getSignName());
            }
            default -> {
                if (complete) {
                    log.info("短信使用阿里云号码认证服务 · 短信认证（sms.provider=auto），签名 {}", config.get().getSignName());
                } else {
                    log.warn("阿里云短信未配置齐全（环境变量与后台配置）：短信暂用 LogSmsClient，验证码只打印到服务器日志；"
                            + "在后台补齐 AccessKey 与签名后立即改用阿里云");
                }
            }
        }
        return client;
    }
}
