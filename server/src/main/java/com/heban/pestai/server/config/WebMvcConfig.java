package com.heban.pestai.server.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.heban.pestai.server.admin.AdminInterceptor;
import com.heban.pestai.server.security.JwtInterceptor;
import com.heban.pestai.server.service.FileStorageService;

/**
 * 接口前缀、拦截器与白名单、/files/** 静态映射、后台管理网页、跨域。
 * <p>
 * 所有 @RestController 统一挂在 /pestai/v1/ 下（不用 context-path，因为 /files/** 不在该前缀下）；
 * 本机开发没有 Nginx，由 Spring Boot 直接把 /files/** 映射到文件根目录。
 * <p>
 * 后台管理（com.heban.pestai.server.admin 包）例外：接口在 /admin/api/** 下、不加 /pestai/v1 前缀、不经过 JwtInterceptor，
 * 由 AdminInterceptor 按会话校验；网页为 classpath:/static/admin/ 下的静态文件，/admin 跳转到 /admin/（index.html）。
 * 网页用到的前端库（Vue、Element Plus、ECharts）放在 static/admin/vendor/ 下，随 jar 一起提供，不依赖外网 CDN。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/pestai/v1";
    /** 后台管理的包：其中的 @RestController 不加 API_PREFIX */
    public static final String ADMIN_PACKAGE = "com.heban.pestai.server.admin";

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
    private final AdminInterceptor adminInterceptor;
    private final FileStorageService fileStorageService;

    public WebMvcConfig(JwtInterceptor jwtInterceptor, AdminInterceptor adminInterceptor,
                        FileStorageService fileStorageService) {
        this.jwtInterceptor = jwtInterceptor;
        this.adminInterceptor = adminInterceptor;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class)
                .and(HandlerTypePredicate.forBasePackage(ADMIN_PACKAGE).negate()));
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
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns(AdminInterceptor.API_PREFIX + "/**")
                .excludePathPatterns(AdminInterceptor.API_PREFIX + "/auth/login");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = fileStorageService.getRoot().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/files/**").addResourceLocations(location);
        // 后台管理网页（接口 /admin/api/** 由控制器处理，优先于静态资源）
        registry.addResourceHandler("/admin/**").addResourceLocations("classpath:/static/admin/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/admin", "/admin/");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // App 不受跨域限制；这里只为浏览器调试工具放开，令牌放在请求头中，不使用 Cookie。
        // 后台接口 /admin/api/** 只允许同源访问（会话 Cookie 为 SameSite=Strict），不配置跨域
        registry.addMapping(API_PREFIX + "/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
