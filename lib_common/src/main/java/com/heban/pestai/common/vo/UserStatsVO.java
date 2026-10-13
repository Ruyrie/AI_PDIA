package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 我的页统计（user/stats）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class UserStatsVO {

    /** 累计识别次数 */
    private int total;
    /** 识别出的病虫害种类数 */
    private int pestKinds;
    /** AI 问答次数 */
    private int aiCount;
    /** 本月识别次数 */
    private int monthCount;
    /** 最近一次识别，没有记录时为空 */
    private RecordItemVO lastRecord;

    public UserStatsVO() {
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPestKinds() {
        return pestKinds;
    }

    public void setPestKinds(int pestKinds) {
        this.pestKinds = pestKinds;
    }

    public int getAiCount() {
        return aiCount;
    }

    public void setAiCount(int aiCount) {
        this.aiCount = aiCount;
    }

    public int getMonthCount() {
        return monthCount;
    }

    public void setMonthCount(int monthCount) {
        this.monthCount = monthCount;
    }

    public RecordItemVO getLastRecord() {
        return lastRecord;
    }

    public void setLastRecord(RecordItemVO lastRecord) {
        this.lastRecord = lastRecord;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UserStatsVO that = (UserStatsVO) o;
        return total == that.total
                && pestKinds == that.pestKinds
                && aiCount == that.aiCount
                && monthCount == that.monthCount
                && Objects.equals(lastRecord, that.lastRecord);
    }

    @Override
    public int hashCode() {
        return Objects.hash(total, pestKinds, aiCount, monthCount, lastRecord);
    }
}
