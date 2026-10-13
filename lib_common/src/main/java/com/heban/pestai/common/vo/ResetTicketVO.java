package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 找回密码凭证（auth/password/verify）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class ResetTicketVO {

    /** 一次性重置凭证 */
    private String resetTicket;
    /** 有效期（秒） */
    private int expireIn;

    public ResetTicketVO() {
    }

    public String getResetTicket() {
        return resetTicket;
    }

    public void setResetTicket(String resetTicket) {
        this.resetTicket = resetTicket;
    }

    public int getExpireIn() {
        return expireIn;
    }

    public void setExpireIn(int expireIn) {
        this.expireIn = expireIn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ResetTicketVO that = (ResetTicketVO) o;
        return Objects.equals(resetTicket, that.resetTicket)
                && expireIn == that.expireIn;
    }

    @Override
    public int hashCode() {
        return Objects.hash(resetTicket, expireIn);
    }
}
