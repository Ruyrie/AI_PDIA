package com.heban.pestai.server;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 病虫害识别助手后端 pestai-server。
 * 接口前缀 /pestai/v1/（见 WebMvcConfig），图片静态资源 /files/**。
 * Mapper 接口带 @Mapper 注解，由 MyBatis Spring Boot Starter 自动扫描本包。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PestaiServerApplication {

    public static void main(String[] args) {
        // 业务时间统一按 Asia/Shanghai（与 App、JDBC serverTimezone 一致）
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(PestaiServerApplication.class, args);
    }
}
