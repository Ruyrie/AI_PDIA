package com.heban.pestai.server.vo;

/** 找回密码凭证：reset_ticket 为一次性明文（只出现在这次响应中），expire_in 单位秒。 */
public record ResetTicketVO(String resetTicket, Integer expireIn) {
}
