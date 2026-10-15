package com.heban.pestai.server.admin.entity;

import java.time.LocalDateTime;

/**
 * 后台用户列表 / 详情的一行：t_user 的界面字段（不含密码、恢复码哈希、token_version）+ 识别次数 + AI 提问次数。
 * recoveryCodeGenerated 由 SQL 按 recovery_code_hash 是否为空算出，哈希本身不读出。
 */
public class AdminUserRow {

    private Long id;
    private String phone;
    private String nickname;
    /** 相对路径 */
    private String avatarUrl;
    private String region;
    private String identity;
    private String crops;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime smsVerifiedAt;
    private Boolean recoveryCodeGenerated;
    /** 全部识别记录（含用户已删除的） */
    private Long recognitionCount;
    /** 所有会话中 role = user 的消息数 */
    private Long aiQuestionCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public String getCrops() {
        return crops;
    }

    public void setCrops(String crops) {
        this.crops = crops;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public LocalDateTime getSmsVerifiedAt() {
        return smsVerifiedAt;
    }

    public void setSmsVerifiedAt(LocalDateTime smsVerifiedAt) {
        this.smsVerifiedAt = smsVerifiedAt;
    }

    public Boolean getRecoveryCodeGenerated() {
        return recoveryCodeGenerated;
    }

    public void setRecoveryCodeGenerated(Boolean recoveryCodeGenerated) {
        this.recoveryCodeGenerated = recoveryCodeGenerated;
    }

    public Long getRecognitionCount() {
        return recognitionCount;
    }

    public void setRecognitionCount(Long recognitionCount) {
        this.recognitionCount = recognitionCount;
    }

    public Long getAiQuestionCount() {
        return aiQuestionCount;
    }

    public void setAiQuestionCount(Long aiQuestionCount) {
        this.aiQuestionCount = aiQuestionCount;
    }
}
