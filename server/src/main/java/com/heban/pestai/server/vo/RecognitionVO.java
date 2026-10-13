package com.heban.pestai.server.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 识别结果详情：RecordItemVO 的全部字段 + category、part、spread_fast、evidence、urgency_days、others、model_name。
 * spread_fast 不为空（默认 false），others 不为空（可为空数组）。
 */
public record RecognitionVO(
        Long id,
        String mode,
        String imageUrl,
        String status,
        String topName,
        BigDecimal confidence,
        String severity,
        String failReason,
        LocalDateTime createdAt,
        String category,
        String part,
        Boolean spreadFast,
        String evidence,
        Integer urgencyDays,
        List<CandidateVO> others,
        String modelName) {
}
