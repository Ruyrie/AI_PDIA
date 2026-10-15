package com.heban.pestai.server.admin.vo;

/** admin/api/auth/login、auth/me：用户名、是否必须先修改初始密码、CSRF 令牌（除 GET 外的请求都要放在请求头 X-CSRF-Token 中） */
public record AdminLoginVO(
        String username,
        Boolean mustChangePassword,
        String csrfToken) {
}
