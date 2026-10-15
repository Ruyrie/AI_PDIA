package com.heban.pestai.server.admin.vo;

import java.time.LocalDateTime;

/** 用户的 AI 会话（至少有 1 条消息），updated_at 为最后一条消息时间 */
public record AdminSessionVO(
        Long sessionId,
        String title,
        int messageCount,
        LocalDateTime updatedAt) {
}
