package com.heban.pestai.server.admin.entity;

/** 后台统计：分组计数的一行（name 为分组值，如识别模式、病虫害名称、种植身份） */
public class NameCount {

    private String name;
    private Long total;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }
}
