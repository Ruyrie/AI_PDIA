package com.heban.pestai.server.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.heban.pestai.server.security.JwtInterceptor;
import com.heban.pestai.server.service.FileStorageService;

/**
 * 接口前缀、拦截器与白名单、/files/** 静态映射、跨域。
 * <p>
 * 所有 @RestController 统一挂在 /pestai/v1/ 下（不用 context-path，因为 /files/** 不在该前缀下）；
 * 本机开发没有 Nginx，由 Spring Boot 直接把 /files/** 映射到文件根目录。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/pestai/v1";

    /** 不需要登录的接口（公共数据 04「1.3 请求处理流程」） */
    public static final List<String> WHITELIST = List.of(
            "auth/sms/send",
            "auth/login/password",
            "auth/login/sms",
            "auth/register",
            "auth/password/verify",
            "auth/password/reset",
            "auth/recover/verify",
            "auth/recover/rebind",
            "app/version");

    private final JwtInterceptor jwtInterceptor;
    private final FileStorageService fileStorageService;

    public WebMvcConfig(JwtInterceptor jwtInterceptor, FileStorageService fileStorageService) {
        this.jwtInterceptor = jwtInterceptor;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> excluded = new java.util.ArrayList<>();
        for (String path : WHITELIST) {
            excluded.add(API_PREFIX + "/" + path);
        }
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns(API_PREFIX + "/**")
                .excludePathPatterns(excluded);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = fileStorageService.getRoot().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/files/**").addResourceLocations(location);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // App 不受跨域限制；这里只为浏览器调试工具放开，令牌放在请求头中，不使用 Cookie
        registry.addMapping(API_PREFIX + "/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
