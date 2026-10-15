package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 其他可能结果（RecognitionVO.others）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 * reason / difference 为后加字段，旧记录没有，均可能为 null。
 */
public class CandidateVO {

    /** 结果名称 */
    private String name;
    /** 匹配度 0–1 */
    private double confidence;
    /** 为什么可能是它：照片中哪些特征与它相似（JSON reason，可为 null） */
    private String reason;
    /** 田间如何与最可能结果区分（JSON difference，可为 null） */
    private String difference;

    public CandidateVO() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDifference() {
        return difference;
    }

    public void setDifference(String difference) {
        this.difference = difference;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CandidateVO that = (CandidateVO) o;
        return Objects.equals(name, that.name)
                && Double.compare(that.confidence, confidence) == 0
                && Objects.equals(reason, that.reason)
                && Objects.equals(difference, that.difference);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, confidence, reason, difference);
    }
}
