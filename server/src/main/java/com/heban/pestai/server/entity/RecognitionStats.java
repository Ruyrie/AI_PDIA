package com.heban.pestai.server.entity;

/** user/stats 的一次聚合查询结果（不对应数据表） */
public class RecognitionStats {

    private Integer total;
    private Integer pestKinds;
    private Integer monthCount;

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getPestKinds() {
        return pestKinds;
    }

    public void setPestKinds(Integer pestKinds) {
        this.pestKinds = pestKinds;
    }

    public Integer getMonthCount() {
        return monthCount;
    }

    public void setMonthCount(Integer monthCount) {
        this.monthCount = monthCount;
    }
}
