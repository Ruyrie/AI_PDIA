package com.heban.pestai.server.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** t_recognition：识别记录（逻辑删除，查询一律带 deleted = 0） */
public class Recognition {

    private Long id;
    private Long userId;
    /** PEST / CROP */
    private String mode;
    /** 相对路径，如 rec/20261013/{uuid}.jpg */
    private String imageUrl;
    /** SUCCESS / IMAGE_BAD / FAILED */
    private String status;
    private String topName;
    /** DECIMAL(4,3) */
    private BigDecimal confidence;
    private String severity;
    private String category;
    private String part;
    private Boolean spreadFast;
    private String evidence;
    private Integer urgencyDays;
    /** CandidateVO 数组的 JSON */
    private String othersJson;
    /** TIMEOUT / UNAVAILABLE / IMAGE_BAD */
    private String failReason;
    private String modelName;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPart() {
        return part;
    }

    public void setPart(String part) {
        this.part = part;
    }

    public Boolean getSpreadFast() {
        return spreadFast;
    }

    public void setSpreadFast(Boolean spreadFast) {
        this.spreadFast = spreadFast;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public Integer getUrgencyDays() {
        return urgencyDays;
    }

    public void setUrgencyDays(Integer urgencyDays) {
        this.urgencyDays = urgencyDays;
    }

    public String getOthersJson() {
        return othersJson;
    }

    public void setOthersJson(String othersJson) {
        this.othersJson = othersJson;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
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
