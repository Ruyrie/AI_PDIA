package com.heban.pestai.server.vo;

import java.time.LocalDateTime;

/**
 * 历史对话列表项（GET ai/sessions）。title 为空时为「新对话」；last_message 为最后一条消息去掉 Markdown 符号后的
 * 纯文字预览（最多 60 字，图片消息以「[图片]」开头），可空；updated_at 为最后一条消息时间。
 */
public record SessionItemVO(
        Long sessionId,
        Long recordId,
        String title,
        String lastMessage,
        Integer messageCount,
        LocalDateTime updatedAt) {
}
