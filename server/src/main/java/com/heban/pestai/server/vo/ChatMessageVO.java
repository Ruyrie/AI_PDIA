package com.heban.pestai.server.vo;

import java.time.LocalDateTime;

/**
 * 对话消息（与 t_ai_message 对应；打开历史对话 GET ai/sessions/{id} 的 messages）。
 * image_url 为完整地址；reasoning_content 为 AI 回答的思考过程全文，reasoning_ms 为思考耗时（毫秒），都可空。
 */
public record ChatMessageVO(
        String role,
        String content,
        String imageUrl,
        LocalDateTime createdAt,
        String reasoningContent,
        Integer reasoningMs) {
}
