package com.heban.pestai.server.vo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 分页：list 当前页、total 总条数、has_more 是否还有下一页。 */
public record PageVO<T>(
        List<T> list,
        Long total,
        @JsonProperty("has_more") Boolean hasMore) {

    public static <T> PageVO<T> empty(long total) {
        return new PageVO<>(List.of(), total, false);
    }
}
