package com.heban.pestai.server.entity;

import java.time.LocalDateTime;

/** t_user */
public class User {

    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_DISABLED = 1;

    private Long id;
    private String phone;
    private String passwordHash;
    private String salt;
    /** 账号恢复码的 BCrypt 哈希；未生成或已用于找回账号后为空 */
    private String recoveryCodeHash;
    /** 恢复码生成时间 */
    private LocalDateTime recoveryCodeCreatedAt;
    private String nickname;
    /** 相对路径，如 avatar/20261013/{uuid}.jpg */
    private String avatarUrl;
    private String region;
    private String identity;
    private String crops;
    private Integer status;
    private Integer tokenVersion;
    private LocalDateTime lastLoginAt;
    /** 最近一次通过短信验证码证明手机号归属的时间（验证码登录、注册、更换手机号、找回账号换绑） */
    private LocalDateTime smsVerifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isDisabled() {
        return status != null && status == STATUS_DISABLED;
    }

    /** 是否已生成恢复码（不用 hasXxx / isXxx 命名，避免被 MyBatis 识别为属性） */
    public boolean recoveryCodeGenerated() {
        return recoveryCodeHash != null && !recoveryCodeHash.isBlank();
    }

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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public String getRecoveryCodeHash() {
        return recoveryCodeHash;
    }

    public void setRecoveryCodeHash(String recoveryCodeHash) {
        this.recoveryCodeHash = recoveryCodeHash;
    }

    public LocalDateTime getRecoveryCodeCreatedAt() {
        return recoveryCodeCreatedAt;
    }

    public void setRecoveryCodeCreatedAt(LocalDateTime recoveryCodeCreatedAt) {
        this.recoveryCodeCreatedAt = recoveryCodeCreatedAt;
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

    public Integer getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public LocalDateTime getSmsVerifiedAt() {
        return smsVerifiedAt;
    }

    public void setSmsVerifiedAt(LocalDateTime smsVerifiedAt) {
        this.smsVerifiedAt = smsVerifiedAt;
    }

    /** 是否在 window 内刚通过短信验证过手机号 */
    public boolean smsVerifiedWithin(java.time.Duration window, LocalDateTime now) {
        return smsVerifiedAt != null && !smsVerifiedAt.isBefore(now.minus(window)) && !smsVerifiedAt.isAfter(now.plusMinutes(1));
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
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
