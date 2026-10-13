package com.heban.pestai.server.service;

import java.math.BigDecimal;
import java.util.List;

import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.vo.CandidateVO;

/**
 * 校验通过的识图结果：status 为 SUCCESS（其余字段已校验）或 IMAGE_BAD（其余字段为空）。
 */
public record ParsedVision(
        String status,
        String topName,
        BigDecimal confidence,
        String severity,
        String category,
        String part,
        boolean spreadFast,
        String evidence,
        Integer urgencyDays,
        List<CandidateVO> others) {

    public static ParsedVision imageBad() {
        return new ParsedVision(RecognitionStatus.IMAGE_BAD, null, null, null, null, null, false, null, null, List.of());
    }

    public boolean isSuccess() {
        return RecognitionStatus.SUCCESS.equals(status);
    }
}
