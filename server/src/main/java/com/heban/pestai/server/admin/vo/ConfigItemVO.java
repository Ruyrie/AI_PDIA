package com.heban.pestai.server.admin.vo;

import java.time.LocalDateTime;

/**
 * 系统配置项：密钥类 value 为掩码（前 3 位 + **** + 后 4 位；不足 12 位只显示 ****；未配置为空串），非密钥类为明文。
 * source：ADMIN（后台设置）/ ENV（环境变量或 .env）/ DEFAULT（默认值）/ NONE（未配置）；
 * updated_at、updated_by 只在 source = ADMIN 时有值。
 */
public record ConfigItemVO(
        String key,
        String label,
        String group,
        boolean secret,
        String value,
        String source,
        LocalDateTime updatedAt,
        String updatedBy) {
}
