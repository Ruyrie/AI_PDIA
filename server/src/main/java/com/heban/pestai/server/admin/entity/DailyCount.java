package com.heban.pestai.server.admin.entity;

import java.time.LocalDate;

/** 后台统计：按天聚合的一行（stat_date 为 DATE(created_at)，Asia/Shanghai）；success 只用于识别次数 */
public class DailyCount {

    private LocalDate statDate;
    private Long total;
    private Long success;

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Long getSuccess() {
        return success;
    }

    public void setSuccess(Long success) {
        this.success = success;
    }
}
