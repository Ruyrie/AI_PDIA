package com.heban.pestai.server.admin.vo;

import java.time.LocalDateTime;

/** 后台操作日志：admin 为管理员用户名（登录失败时为尝试登录的用户名） */
public record AdminLogVO(
        Long id,
        String admin,
        String action,
        String target,
        String detail,
        String ip,
        LocalDateTime createdAt) {
}
