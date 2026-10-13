package com.heban.pestai.server.vo;

/** 我的页统计：last_record 没有记录时为 null。 */
public record UserStatsVO(
        Integer total,
        Integer pestKinds,
        Integer aiCount,
        Integer monthCount,
        RecordItemVO lastRecord) {
}
