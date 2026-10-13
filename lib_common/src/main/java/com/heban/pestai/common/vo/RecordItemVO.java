package com.heban.pestai.common.vo;

import java.util.Date;
import java.util.Objects;

/**
 * 识别记录列表项（recognitions/recent、recognitions 分页、UserStatsVO.last_record）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class RecordItemVO {

    /** 记录 ID */
    private long id;
    /** PEST / CROP（见 Mode） */
    private String mode;
    /** 识别照片地址 */
    private String imageUrl;
    /** SUCCESS / IMAGE_BAD / FAILED（见 RecognitionStatus） */
    private String status;
    /** 最可能结果，失败时为空 */
    private String topName;
    /** 匹配度 0–1，失败时为空 */
    private Double confidence;
    /** 健康 / 轻度 / 中度 / 较重（见 Severity），可空 */
    private String severity;
    /** TIMEOUT / UNAVAILABLE / IMAGE_BAD（见 FailReason），成功时为空 */
    private String failReason;
    /** 识别时间 */
    private Date createdAt;

    public RecordItemVO() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTopName() {
        return topName;
    }

    public void setTopName(String topName) {
        this.topName = topName;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RecordItemVO that = (RecordItemVO) o;
        return id == that.id
                && Objects.equals(mode, that.mode)
                && Objects.equals(imageUrl, that.imageUrl)
                && Objects.equals(status, that.status)
                && Objects.equals(topName, that.topName)
                && Objects.equals(confidence, that.confidence)
                && Objects.equals(severity, that.severity)
                && Objects.equals(failReason, that.failReason)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, mode, imageUrl, status, topName, confidence, severity, failReason, createdAt);
    }
}
