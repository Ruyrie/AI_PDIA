package com.heban.pestai.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.heban.pestai.server.client.ChatClient;
import com.heban.pestai.server.client.SmsClient;
import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.service.FileStorageService;

/**
 * 启动完成后打印运行配置摘要（不打印任何密钥），提醒哪些功能处于 Mock / 开发模式。
 */
@Component
public class StartupReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReporter.class);

    private final Environment environment;
    private final PestaiProperties properties;
    private final FileStorageService fileStorageService;
    private final JwtTokenProvider tokenProvider;
    private final VisionClient visionClient;
    private final ChatClient chatClient;
    private final SmsClient smsClient;

    public StartupReporter(Environment environment, PestaiProperties properties, FileStorageService fileStorageService,
                           JwtTokenProvider tokenProvider, VisionClient visionClient, ChatClient chatClient,
                           SmsClient smsClient) {
        this.environment = environment;
        this.properties = properties;
        this.fileStorageService = fileStorageService;
        this.tokenProvider = tokenProvider;
        this.visionClient = visionClient;
        this.chatClient = chatClient;
        this.smsClient = smsClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        log.info("==================== pestai-server 已启动 ====================");
        log.info("接口地址：http://localhost:{}{}/（App 使用 {}{}/）", port, WebMvcConfig.API_PREFIX,
                properties.getPublicBaseUrl(), WebMvcConfig.API_PREFIX);
        log.info("图片目录：{}（对外地址 {}/files/）", fileStorageService.getRoot(), properties.getPublicBaseUrl());
        log.info("识图：{}", visionClient.isMock() ? "MockVisionClient（未配置 DASHSCOPE_API_KEY）" : "通义千问（模型见上方 ClientConfig 日志）");
        log.info("问答：{}", chatClient.isMock() ? "MockChatClient（未配置 DEEPSEEK_API_KEY）" : "DeepSeek（模型见上方 ClientConfig 日志）");
        log.info("短信：{}", smsClient.name());
        if (tokenProvider.isUsingDevSecret()) {
            log.warn("JWT：正在使用开发默认密钥，生产环境必须配置环境变量 JWT_SECRET（至少 32 字节的随机串）");
        }
        log.info("==============================================================");
    }
}
