package com.heban.pestai.server.admin.vo;

import java.time.LocalDateTime;

/** 用户详情：UserRow 的全部字段 + 地区、是否已生成恢复码、最近短信验证时间、会话数（至少有 1 条消息的会话） */
public record AdminUserDetailVO(
        Long id,
        String phone,
        String nickname,
        String avatarUrl,
        String identity,
        String crops,
        String status,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        long recognitionCount,
        long aiQuestionCount,
        String region,
        boolean recoveryCodeGenerated,
        LocalDateTime smsVerifiedAt,
        long sessionCount) {
}
