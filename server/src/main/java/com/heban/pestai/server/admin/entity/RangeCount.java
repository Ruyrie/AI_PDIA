package com.heban.pestai.server.admin.entity;

/**
 * 后台统计：一段时间内的计数。total 为总数；part 为其中满足条件的部分
 * （识别：status = SUCCESS 的条数；AI 提问：带图提问的条数；新增用户：已停用的条数等，见各 SQL）。
 */
public class RangeCount {

    private Long total;
    private Long part;

    public long totalOrZero() {
        return total == null ? 0 : total;
    }

    public long partOrZero() {
        return part == null ? 0 : part;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Long getPart() {
        return part;
    }

    public void setPart(Long part) {
        this.part = part;
    }
}
