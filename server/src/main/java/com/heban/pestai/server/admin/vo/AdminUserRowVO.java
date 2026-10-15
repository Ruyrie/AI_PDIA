package com.heban.pestai.server.admin.vo;

import java.time.LocalDateTime;

/**
 * 用户列表的一行（后台显示完整手机号）。status 为 NORMAL / DISABLED；avatar_url 为完整地址，可空；
 * recognition_count 含用户已删除的识别记录；ai_question_count 为全部会话中的提问数。
 */
public record AdminUserRowVO(
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
        long aiQuestionCount) {
}
