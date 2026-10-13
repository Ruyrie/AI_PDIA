package com.heban.pestai.common.vo;

import java.util.Date;
import java.util.Objects;

/**
 * 对话消息（流式片段拼接完成后的一条消息；历史对话 ai/sessions/{id} 的 messages）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class ChatMessageVO {

    /** user / assistant（见 ChatRole） */
    private String role;
    /** 消息文字 */
    private String content;
    /** 带图提问时的图片地址，可空 */
    private String imageUrl;
    /** 发送时间 */
    private Date createdAt;
    /** AI 回答的思考过程全文，可空 */
    private String reasoningContent;
    /** 思考耗时（毫秒），只在有思考内容时有值 */
    private Integer reasoningMs;

    public ChatMessageVO() {
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public Integer getReasoningMs() {
        return reasoningMs;
    }

    public void setReasoningMs(Integer reasoningMs) {
        this.reasoningMs = reasoningMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ChatMessageVO that = (ChatMessageVO) o;
        return Objects.equals(role, that.role)
                && Objects.equals(content, that.content)
                && Objects.equals(imageUrl, that.imageUrl)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(reasoningContent, that.reasoningContent)
                && Objects.equals(reasoningMs, that.reasoningMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content, imageUrl, createdAt, reasoningContent, reasoningMs);
    }
}
