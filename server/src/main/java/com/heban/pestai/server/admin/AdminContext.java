package com.heban.pestai.server.admin;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;

/**
 * 当前后台请求的管理员（ThreadLocal）。由 AdminInterceptor 写入，请求结束时清除。
 */
public final class AdminContext {

    /** 已登录的管理员：ID、用户名、客户端 IP */
    public record Principal(long id, String username, String ip) {
    }

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private AdminContext() {
    }

    static void set(Principal principal) {
        CURRENT.set(principal);
    }

    /** 当前管理员；未登录时为 null */
    public static Principal get() {
        return CURRENT.get();
    }

    /** 当前管理员；未登录时抛出 40101「请先登录」 */
    public static Principal require() {
        Principal principal = CURRENT.get();
        if (principal == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID, AdminInterceptor.NOT_LOGGED_IN);
        }
        return principal;
    }

    static void clear() {
        CURRENT.remove();
    }
}
