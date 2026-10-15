package com.heban.pestai.server.entity;

import java.time.LocalDateTime;

/** t_ai_message_image：带图提问的一张图片（一条消息最多 9 张，sort_no 从 0 开始）；写入后不再修改 */
public class AiMessageImage {

    private Long id;
    private Long messageId;
    /** 图片顺序，从 0 开始（用户选择的顺序） */
    private Integer sortNo;
    /** 相对路径，如 chat/20261014/{uuid}.jpg */
    private String imageUrl;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
