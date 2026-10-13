package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * AI 对话会话（ai/sessions）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class SessionVO {

    /** 会话 ID */
    private long sessionId;
    /** 关联的识别记录，追问识别结果时有值 */
    private Long recordId;
    /** 会话标题 */
    private String title;

    public SessionVO() {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SessionVO that = (SessionVO) o;
        return sessionId == that.sessionId
                && Objects.equals(recordId, that.recordId)
                && Objects.equals(title, that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, recordId, title);
    }
}
