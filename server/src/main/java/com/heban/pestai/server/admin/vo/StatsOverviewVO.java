package com.heban.pestai.server.admin.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * admin/api/stats/overview。比率为 0–1 的小数（保留 4 位；分母为 0 时为 0）。
 * 带数字的字段名（active_7d 等）用 @JsonProperty 写明：SNAKE_CASE 不会在数字前加下划线。
 */
public record StatsOverviewVO(
        Users users,
        Recognitions recognitions,
        Ai ai,
        Sms sms,
        Fallback fallback) {

    /** 用户：总数、今日新增、近 7 天（含今天）活跃、已停用 */
    public record Users(
            long total,
            long todayNew,
            @JsonProperty("active_7d") long active7d,
            long disabled) {
    }

    /** 识别：总次数（含用户已删除的记录）、今日次数、近 7 天成功率 */
    public record Recognitions(
            long total,
            long today,
            @JsonProperty("success_rate_7d") double successRate7d) {
    }

    /** AI 问答：提问总数、今日提问、近 7 天带图提问占比 */
    public record Ai(
            long questionsTotal,
            long questionsToday,
            @JsonProperty("image_question_ratio_7d") double imageQuestionRatio7d) {
    }

    /** 短信验证码：今日发送、近 7 天发送 */
    public record Sms(
            long today,
            @JsonProperty("last_7d") long last7d) {
    }

    /** 模型自动备用：近 7 天由备用模型完成的 AI 回答数、识别数 */
    public record Fallback(
            @JsonProperty("chat_7d") long chat7d,
            @JsonProperty("vision_7d") long vision7d) {
    }
}
