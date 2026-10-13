package com.heban.pestai.server.common;

/**
 * 找回账号时验证身份的方式：登录密码 / 账号恢复码。与 rebind_ticket 的用途（t_reset_ticket.purpose）一一对应。
 */
public enum RecoverMethod {
    PASSWORD, RECOVERY_CODE;

    /** 本方式签发的凭证用途 */
    public String ticketPurpose() {
        return switch (this) {
            case PASSWORD -> TicketPurpose.REBIND_PASSWORD;
            case RECOVERY_CODE -> TicketPurpose.REBIND_RECOVERY;
        };
    }

    /** 由凭证用途反查验证方式；不是找回账号的凭证（如找回密码的 RESET）返回 null */
    public static RecoverMethod fromTicketPurpose(String purpose) {
        if (TicketPurpose.REBIND_PASSWORD.equals(purpose)) {
            return PASSWORD;
        }
        if (TicketPurpose.REBIND_RECOVERY.equals(purpose)) {
            return RECOVERY_CODE;
        }
        return null;
    }
}
