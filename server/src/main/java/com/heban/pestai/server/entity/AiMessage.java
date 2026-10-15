package com.heban.pestai.server.entity;

import java.time.LocalDateTime;
import java.util.List;

/** t_ai_message */
public class AiMessage {

    private Long id;
    private Long sessionId;
    /** user / assistant */
    private String role;
    private String content;
    /** 相对路径，如 chat/20261013/{uuid}.jpg；只有带图提问的 user 消息有值，多图时为第一张（全部图片见 t_ai_message_image） */
    private String imageUrl;
    /**
     * 本条消息的全部图片相对路径（按 sort_no），只用于读取：查询消息后由 ChatImages.attach 从 t_ai_message_image 填入，
     * 不对应 t_ai_message 的列；没有图片行的旧消息为 null（读取时退回 [image_url]，见 ChatImages.of）
     */
    private List<String> imageUrls;
    /** 思考过程全文；只有带思考的 assistant 消息有值 */
    private String reasoningContent;
    /** 从开始请求上游到收到第一个正文片段的毫秒数；只在有思考内容且正文已开始时有值 */
    private Integer reasoningMs;
    /** 生成回答的模型名（迁移 007；如 deepseek-flash、qwen3.8-max，Mock 为 mock）：只有 assistant 消息有值，用户消息与旧数据为 null */
    private String model;
    /** 是否由备用模型回答（迁移 007，TINYINT(1) NOT NULL DEFAULT 0）；默认 false，用户消息始终为 false */
    private Boolean fallback = Boolean.FALSE;
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

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Boolean getFallback() {
        return fallback;
    }

    public void setFallback(Boolean fallback) {
        this.fallback = fallback;
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
