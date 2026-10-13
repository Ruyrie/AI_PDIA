package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 其他可能结果（RecognitionVO.others）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class CandidateVO {

    /** 结果名称 */
    private String name;
    /** 匹配度 0–1 */
    private double confidence;

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
                && Double.compare(that.confidence, confidence) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, confidence);
    }
}
