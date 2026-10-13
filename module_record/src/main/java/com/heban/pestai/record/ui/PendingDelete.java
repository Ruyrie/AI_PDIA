package com.heban.pestai.record.ui;

import androidx.annotation.NonNull;

import com.heban.pestai.common.vo.RecordItemVO;

/**
 * 等待撤销的删除：被删的记录、原位置、截止时间（SystemClock.elapsedRealtime() 基准，不受修改系统时间影响）、
 * 删除时的筛选（提交失败恢复记录时判断是否还能插回原位置）。
 */
public final class PendingDelete {

    @NonNull
    private final RecordItemVO record;
    private final int index;
    private final long deadline;
    @NonNull
    private final HistoryFilter filter;

    PendingDelete(@NonNull RecordItemVO record, int index, long deadline, @NonNull HistoryFilter filter) {
        this.record = record;
        this.index = index;
        this.deadline = deadline;
        this.filter = filter;
    }

    @NonNull
    public RecordItemVO getRecord() {
        return record;
    }

    public int getIndex() {
        return index;
    }

    public long getDeadline() {
        return deadline;
    }

    @NonNull
    public HistoryFilter getFilter() {
        return filter;
    }
}
