package com.heban.pestai.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 流式回答线程池 aiStreamExecutor：核心 8、最大 32、队列 100。生成阶段在这里执行，不占用 Tomcat 请求线程。
 */
@Configuration
public class AiAsyncConfig {

    public static final String AI_STREAM_EXECUTOR = "aiStreamExecutor";

    @Bean(name = AI_STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor aiStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
