package com.heban.pestai.server.entity;

import java.time.LocalDateTime;

/** t_ai_message */
public class AiMessage {

    private Long id;
    private Long sessionId;
    /** user / assistant */
    private String role;
    private String content;
    /** 相对路径，如 chat/20261013/{uuid}.jpg；只有带图提问的 user 消息有值 */
    private String imageUrl;
    /** 思考过程全文；只有带思考的 assistant 消息有值 */
    private String reasoningContent;
    /** 从开始请求上游到收到第一个正文片段的毫秒数；只在有思考内容且正文已开始时有值 */
    private Integer reasoningMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AiMessage() {
    }

    public AiMessage(Long sessionId, String role, String content, String imageUrl, LocalDateTime now) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.imageUrl = imageUrl;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
