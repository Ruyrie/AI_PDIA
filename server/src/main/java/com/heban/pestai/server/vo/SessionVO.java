package com.heban.pestai.server.vo;

/** AI 对话会话：record_id 追问识别结果时有值，title 可空。 */
public record SessionVO(Long sessionId, Long recordId, String title) {
}
