package com.heban.pestai.server.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.UserMapper;

/**
 * 登录校验（白名单由 WebMvcConfig 排除）：取 Authorization: Bearer 令牌 → 校验签名与过期 →
 * 按 userId 查 t_user 比对 token_version 并确认账号未停用 → userId 放入 UserContext。
 * 任一项失败（含用户不存在 —— 如账号已注销、已停用）抛出 40101，由 GlobalExceptionHandler 输出。请求结束时清除 UserContext。
 */
@Component
public class JwtInterceptor implements AsyncHandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserMapper userMapper;

    public JwtInterceptor(JwtTokenProvider tokenProvider, UserMapper userMapper) {
        this.tokenProvider = tokenProvider;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 流式回答结束时的异步分派：首次分派已校验过，不再重复查询
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        UserContext.clear();
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        JwtTokenProvider.TokenClaims claims = tokenProvider.parse(header.substring(BEARER.length()).trim());
        User user = userMapper.selectAuthInfo(claims.userId());
        if (user == null || user.isDisabled() || user.getTokenVersion() == null
                || user.getTokenVersion() != claims.tokenVersion()) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        UserContext.set(claims.userId());
        return true;
    }

    /** 进入异步模式（SseEmitter）时请求线程被释放，立即清除 */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContext.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
