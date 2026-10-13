package com.heban.pestai.server.security;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;

/**
 * 当前请求的用户 ID（ThreadLocal）。由 JwtInterceptor 写入，请求结束时清除，防止线程复用时串号。
 * 注意：ThreadLocal 不会传递到异步线程（如流式回答的 aiStreamExecutor），需在请求线程先取出。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    /** 当前用户 ID；未登录时为 null */
    public static Long get() {
        return USER_ID.get();
    }

    /** 当前用户 ID；未登录时抛出 40101 */
    public static long requireUserId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        return userId;
    }

    public static void clear() {
        USER_ID.remove();
    }
}
