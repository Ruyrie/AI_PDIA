package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 大家常问（ai/suggestions）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class SuggestionVO {

    /** 问题 ID */
    private long id;
    /** 问题文字 */
    private String text;
    /** 这条问题匹配到的用户作物（如「番茄」），可空：没有匹配到作物或旧版服务端不返回时为 null */
    private String crop;

    public SuggestionVO() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getCrop() {
        return crop;
    }

    public void setCrop(String crop) {
        this.crop = crop;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SuggestionVO that = (SuggestionVO) o;
        return id == that.id
                && Objects.equals(text, that.text)
                && Objects.equals(crop, that.crop);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, text, crop);
    }
}
