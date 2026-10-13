package com.heban.pestai.common.vo;

import java.util.List;
import java.util.Objects;

/**
 * 识别结果详情（recognitions 上传识别、recognitions/{id}、recognitions/{id}/retry），包含 RecordItemVO 全部字段。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class RecognitionVO extends RecordItemVO {

    /** 类别，如 卵菌病害 */
    private String category;
    /** 发病部位，如 叶部 */
    private String part;
    /** 是否传播快 */
    private boolean spreadFast;
    /** 识别依据 */
    private String evidence;
    /** 建议几天内处理，可空 */
    private Integer urgencyDays;
    /** 其他可能结果，最多 3 条，可为空数组 */
    private List<CandidateVO> others;
    /** 识别所用模型的显示名，界面显示为「由 xx 识别」 */
    private String modelName;

    public RecognitionVO() {
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPart() {
        return part;
    }

    public void setPart(String part) {
        this.part = part;
    }

    public boolean isSpreadFast() {
        return spreadFast;
    }

    public void setSpreadFast(boolean spreadFast) {
        this.spreadFast = spreadFast;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public Integer getUrgencyDays() {
        return urgencyDays;
    }

    public void setUrgencyDays(Integer urgencyDays) {
        this.urgencyDays = urgencyDays;
    }

    public List<CandidateVO> getOthers() {
        return others;
    }

    public void setOthers(List<CandidateVO> others) {
        this.others = others;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        RecognitionVO that = (RecognitionVO) o;
        return Objects.equals(category, that.category)
                && Objects.equals(part, that.part)
                && spreadFast == that.spreadFast
                && Objects.equals(evidence, that.evidence)
                && Objects.equals(urgencyDays, that.urgencyDays)
                && Objects.equals(others, that.others)
                && Objects.equals(modelName, that.modelName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), category, part, spreadFast, evidence, urgencyDays, others, modelName);
    }
}
