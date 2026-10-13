package com.heban.pestai.common.vo;

import java.util.Date;
import java.util.Objects;

/**
 * 历史对话列表项（ai/sessions 分页）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class SessionItemVO {

    /** 会话 ID */
    private long sessionId;
    /** 关联的识别记录，追问识别结果时有值 */
    private Long recordId;
    /** 会话标题 */
    private String title;
    /** 最后一条消息的纯文字预览，最多 60 字 */
    private String lastMessage;
    /** 消息条数 */
    private int messageCount;
    /** 最后一条消息时间 */
    private Date updatedAt;

    public SessionItemVO() {
    }

    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
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

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SessionItemVO that = (SessionItemVO) o;
        return sessionId == that.sessionId
                && Objects.equals(recordId, that.recordId)
                && Objects.equals(title, that.title)
                && Objects.equals(lastMessage, that.lastMessage)
                && messageCount == that.messageCount
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, recordId, title, lastMessage, messageCount, updatedAt);
    }
}
