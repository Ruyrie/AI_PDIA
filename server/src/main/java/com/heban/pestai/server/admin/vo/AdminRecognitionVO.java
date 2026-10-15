package com.heban.pestai.server.admin.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户的识别记录（含用户已删除的，deleted = true）；image_url 为完整地址；失败记录的 top_name、confidence、severity 为 null；
 * model 为给出结果的模型名（失败记录与迁移 007 之前的旧记录为 null），fallback 为是否由备用模型给出结果。
 */
public record AdminRecognitionVO(
        Long id,
        String mode,
        String status,
        String topName,
        BigDecimal confidence,
        String severity,
        String imageUrl,
        LocalDateTime createdAt,
        boolean deleted,
        String model,
        boolean fallback) {
}
