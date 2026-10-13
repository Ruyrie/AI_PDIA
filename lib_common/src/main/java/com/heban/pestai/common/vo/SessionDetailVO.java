package com.heban.pestai.common.vo;

import java.util.List;
import java.util.Objects;

/**
 * 打开历史对话（ai/sessions/{id}）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class SessionDetailVO {

    /** 会话 ID */
    private long sessionId;
    /** 关联的识别记录 */
    private Long recordId;
    /** 会话标题 */
    private String title;
    /** 关联记录摘要，记录已删除时为空 */
    private RecordItemVO record;
    /** 全部消息，按时间正序 */
    private List<ChatMessageVO> messages;

    public SessionDetailVO() {
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

    public RecordItemVO getRecord() {
        return record;
    }

    public void setRecord(RecordItemVO record) {
        this.record = record;
    }

    public List<ChatMessageVO> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessageVO> messages) {
        this.messages = messages;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SessionDetailVO that = (SessionDetailVO) o;
        return sessionId == that.sessionId
                && Objects.equals(recordId, that.recordId)
                && Objects.equals(title, that.title)
                && Objects.equals(record, that.record)
                && Objects.equals(messages, that.messages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, recordId, title, record, messages);
    }
}
