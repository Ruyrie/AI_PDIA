package com.heban.pestai.server.admin.vo;

import java.util.List;

/**
 * admin/api/stats/trend：各数组与 dates 等长，按天（yyyy-MM-dd，含今天），没有数据的日期为 0。
 * fallback_used 为每天由备用模型完成的 AI 回答数 + 识别数。
 */
public record StatsTrendVO(
        List<String> dates,
        List<Long> newUsers,
        List<Long> activeUsers,
        List<Long> recognitions,
        List<Long> recognitionSuccess,
        List<Long> aiQuestions,
        List<Long> smsSent,
        List<Long> fallbackUsed) {
}
