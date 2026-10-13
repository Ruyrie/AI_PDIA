package com.heban.pestai.server.vo;

import java.util.List;

/**
 * 打开历史对话（GET ai/sessions/{id}）：会话信息 + 关联识别记录摘要（记录不存在或已删除时为 null）+ 全部消息（时间正序）。
 */
public record SessionDetailVO(
        Long sessionId,
        Long recordId,
        String title,
        RecordItemVO record,
        List<ChatMessageVO> messages) {
}
