package com.heban.pestai.server.vo;

/**
 * 找回账号凭证（auth/recover/verify）：rebind_ticket 为一次性明文（只出现在这次响应中，32 位），expire_in 单位秒，
 * method 为本次验证身份的方式 PASSWORD / RECOVERY_CODE（为 RECOVERY_CODE 时换绑必须同时设置新密码）。
 */
public record RebindTicketVO(String rebindTicket, Integer expireIn, String method) {
}
