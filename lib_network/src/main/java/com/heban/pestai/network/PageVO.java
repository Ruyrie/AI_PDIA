package com.heban.pestai.network;

import java.util.Collections;
import java.util.List;

/**
 * 分页结构：list 为当前页 VO 数组，total 为总条数，has_more 表示是否还有下一页。
 */
public class PageVO<T> {

    private List<T> list;
    private int total;
    private boolean hasMore;

    public PageVO() {
    }

    public List<T> getList() {
        return list == null ? Collections.emptyList() : list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }
}
