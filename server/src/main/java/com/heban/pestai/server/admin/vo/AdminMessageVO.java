package com.heban.pestai.server.admin.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话中的一条消息（只读）：image_urls 为完整地址（没有图片为空数组）；reasoning_content 为 AI 回答的思考过程，可空；
 * model 为回答所用模型名（用户消息与迁移 007 之前的旧数据为 null），fallback 为是否由备用模型回答。
 */
public record AdminMessageVO(
        String role,
        String content,
        List<String> imageUrls,
        String reasoningContent,
        LocalDateTime createdAt,
        String model,
        boolean fallback) {
}
