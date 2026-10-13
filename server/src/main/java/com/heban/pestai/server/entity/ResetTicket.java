package com.heban.pestai.server.entity;

import java.time.LocalDateTime;

/**
 * t_reset_ticket：一次性凭证，只保存 SHA-256 摘要。purpose 区分用途（见 TicketPurpose）：
 * 找回密码的 reset_ticket（RESET）与找回账号的 rebind_ticket（REBIND_PASSWORD / REBIND_RECOVERY）。
 */
public class ResetTicket {

    private Long id;
    private Long userId;
    private String phone;
    private String ticketHash;
    /** RESET / REBIND_PASSWORD / REBIND_RECOVERY */
    private String purpose;
    private LocalDateTime expireAt;
    /** 0 未使用 / 1 已使用或已作废 */
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getTicketHash() {
        return ticketHash;
    }

    public void setTicketHash(String ticketHash) {
        this.ticketHash = ticketHash;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
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
