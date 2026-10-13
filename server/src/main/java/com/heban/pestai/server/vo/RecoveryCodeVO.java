package com.heban.pestai.server.vo;

import java.time.LocalDateTime;

/**
 * 账号恢复码状态（user/recovery-code）：generated 是否已生成；created_at 生成时间（未生成时为 null）；
 * recovery_code 只在生成 / 重新生成的那次响应中返回明文（ABCD-EFGH-JKLM），其余情况为 null；
 * verify_required 生成时是否需要短信验证码（10 分钟内刚通过短信验证手机号时为 false）。
 */
public record RecoveryCodeVO(Boolean generated, LocalDateTime createdAt, String recoveryCode, Boolean verifyRequired) {
}
