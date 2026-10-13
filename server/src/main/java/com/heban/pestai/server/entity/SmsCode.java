package com.heban.pestai.server.entity;

import java.time.LocalDateTime;

/** t_sms_code */
public class SmsCode {

    private Long id;
    private String phone;
    private String scene;
    private String code;
    private LocalDateTime expireAt;
    /** 0 未使用 / 1 已使用 */
    private Integer used;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 是否已使用（不用 isUsed 命名，避免与 getUsed 冲突被 MyBatis 识别为重载的 getter） */
    public boolean alreadyUsed() {
        return used != null && used != 0;
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

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public Integer getUsed() {
        return used;
    }

    public void setUsed(Integer used) {
        this.used = used;
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
