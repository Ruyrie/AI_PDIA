package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 找回账号的身份凭证（auth/recover/verify）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class RebindTicketVO {

    /** 一次性换绑凭证，10 分钟有效 */
    private String rebindTicket;
    /** 有效期（秒） */
    private int expireIn;
    /** 验证方式：PASSWORD / RECOVERY_CODE（见 RecoverMethod）；RECOVERY_CODE 时换绑必须设置新密码 */
    private String method;

    public RebindTicketVO() {
    }

    public String getRebindTicket() {
        return rebindTicket;
    }

    public void setRebindTicket(String rebindTicket) {
        this.rebindTicket = rebindTicket;
    }

    public int getExpireIn() {
        return expireIn;
    }

    public void setExpireIn(int expireIn) {
        this.expireIn = expireIn;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RebindTicketVO that = (RebindTicketVO) o;
        return Objects.equals(rebindTicket, that.rebindTicket)
                && expireIn == that.expireIn
                && Objects.equals(method, that.method);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rebindTicket, expireIn, method);
    }
}
