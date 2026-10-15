package com.heban.pestai.server.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.heban.pestai.server.admin.entity.Admin;
import com.heban.pestai.server.admin.mapper.AdminMapper;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;

/**
 * 后台接口 /admin/api/** 的登录校验（auth/login 由 WebMvcConfig 排除）：
 * <ol>
 *   <li>会话中没有管理员 ID → 40101「请先登录」；管理员已不存在 → 作废会话并返回 40101；</li>
 *   <li>除 GET / HEAD / OPTIONS 外，请求头 X-CSRF-Token 必须与会话中的令牌一致（定长比较），否则 40301；</li>
 *   <li>初始密码未修改时，除 auth/me、auth/password、auth/logout 外返回 40302「请先修改初始密码」；</li>
 *   <li>通过后把管理员放入 AdminContext，请求结束时清除。</li>
 * </ol>
 * 与 App 的 JwtInterceptor 互不相干：后台接口不在 /pestai/v1 下，也不接受 App 的令牌。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    public static final String API_PREFIX = "/admin/api";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String NOT_LOGGED_IN = "请先登录";

    /** 初始密码未修改时仍可访问的接口 */
    private static final Set<String> ALLOWED_BEFORE_PASSWORD_CHANGE = Set.of(
            API_PREFIX + "/auth/me", API_PREFIX + "/auth/password", API_PREFIX + "/auth/logout");

    private final AdminMapper adminMapper;

    public AdminInterceptor(AdminMapper adminMapper) {
        this.adminMapper = adminMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminContext.clear();
        String method = request.getMethod();
        if (HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        HttpSession session = request.getSession(false);
        Object adminId = session == null ? null : session.getAttribute(AdminAuthService.ATTR_ADMIN_ID);
        if (!(adminId instanceof Long id)) {
            throw new BizException(ErrorCode.TOKEN_INVALID, NOT_LOGGED_IN);
        }
        Admin admin = adminMapper.selectById(id);
        if (admin == null) {
            invalidateQuietly(session);
            throw new BizException(ErrorCode.TOKEN_INVALID, NOT_LOGGED_IN);
        }
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)) {
            Object expected = session.getAttribute(AdminAuthService.ATTR_CSRF_TOKEN);
            if (!(expected instanceof String token) || !constantTimeEquals(token, request.getHeader(CSRF_HEADER))) {
                throw new BizException(ErrorCode.FORBIDDEN);
            }
        }
        if (admin.mustChange() && !ALLOWED_BEFORE_PASSWORD_CHANGE.contains(pathOf(request))) {
            throw new BizException(ErrorCode.MUST_CHANGE_PASSWORD);
        }
        AdminContext.set(new AdminContext.Principal(admin.getId(), admin.getUsername(), ClientIp.of(request)));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AdminContext.clear();
    }

    /** 去掉 context path 与末尾的 / */
    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        while (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void invalidateQuietly(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException e) {
            // 会话已失效
        }
    }
}
