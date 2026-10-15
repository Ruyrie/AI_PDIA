package com.heban.pestai.server.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话消息（与 t_ai_message 对应；打开历史对话 GET ai/sessions/{id} 的 messages）。
 * image_urls 为本条消息的全部图片完整地址（按顺序，最多 9 张；没有图片为空列表，不为 null）；
 * image_url 为第一张图片的完整地址（兼容旧版 App，没有图片为 null）；只发图片的提问 content 为空字符串。
 * reasoning_content 为 AI 回答的思考过程全文，reasoning_ms 为思考耗时（毫秒），都可空。
 */
public record ChatMessageVO(
        String role,
        String content,
        String imageUrl,
        List<String> imageUrls,
        LocalDateTime createdAt,
        String reasoningContent,
        Integer reasoningMs) {

    public ChatMessageVO {
        imageUrls = imageUrls == null ? List.of() : imageUrls;
    }
}
