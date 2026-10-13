package com.heban.pestai.server.entity;

import java.time.LocalDateTime;

/** 历史对话列表的一行：t_ai_session 的会话信息 + 消息条数 + 最后一条可显示消息（AiSessionMapper.selectPageWithMessages） */
public class AiSessionSummary {

    private Long id;
    private Long recordId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer messageCount;
    /** 最后一条有文字或图片的消息（只有思考、正文为空的回答会被跳过）；没有时为 null */
    private String lastRole;
    private String lastContent;
    private String lastImageUrl;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount) {
        this.messageCount = messageCount;
    }

    public String getLastRole() {
        return lastRole;
    }

    public void setLastRole(String lastRole) {
        this.lastRole = lastRole;
    }

    public String getLastContent() {
        return lastContent;
    }

    public void setLastContent(String lastContent) {
        this.lastContent = lastContent;
    }

    public String getLastImageUrl() {
        return lastImageUrl;
    }

    public void setLastImageUrl(String lastImageUrl) {
        this.lastImageUrl = lastImageUrl;
    }
}
