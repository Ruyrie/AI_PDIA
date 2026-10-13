package com.heban.pestai.server.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 识别记录列表项（首页最近识别、识别记录分页、UserStatsVO.last_record）。
 * status 不是 SUCCESS 时 top_name、confidence、severity 为 null；fail_reason 成功时为 null。
 */
public record RecordItemVO(
        Long id,
        String mode,
        String imageUrl,
        String status,
        String topName,
        BigDecimal confidence,
        String severity,
        String failReason,
        LocalDateTime createdAt) {
}
