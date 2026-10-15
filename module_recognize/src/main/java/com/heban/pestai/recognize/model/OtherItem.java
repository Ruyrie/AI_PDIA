package com.heban.pestai.recognize.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.CandidateVO;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * R01「其他可能」的一行：CandidateVO 的显示字段 + 展开状态。
 * <ul>
 *     <li>reason / difference 去掉首尾空白，空白视为没有；两者都没有的行不可展开（无箭头、不可点击）；</li>
 *     <li>reserveChevron：列表中有任一行可展开时为 true，不可展开的行为箭头保留占位，使各行进度条对齐；</li>
 *     <li>equals / hashCode 包含全部字段（含 expanded），供 DiffUtil 判断内容是否变化。</li>
 * </ul>
 */
public final class OtherItem {

    @Nullable
    private final String name;
    private final double confidence;
    @Nullable
    private final String reason;
    @Nullable
    private final String difference;
    /** 「与{最可能结果}的区别」 */
    @NonNull
    private final String differenceLabel;
    private final boolean expanded;
    private final boolean reserveChevron;

    public OtherItem(@Nullable String name, double confidence, @Nullable String reason, @Nullable String difference,
                     @NonNull String differenceLabel, boolean expanded, boolean reserveChevron) {
        this.name = name;
        this.confidence = confidence;
        this.reason = trimToNull(reason);
        this.difference = trimToNull(difference);
        this.differenceLabel = differenceLabel;
        this.expanded = expanded && isExpandable();
        this.reserveChevron = reserveChevron;
    }

    /**
     * CandidateVO 列表 → 行列表（跳过 null 元素）。
     *
     * @param differenceLabel 「与{最可能结果}的区别」
     * @param expandedNames   已展开的候选名称
     */
    @NonNull
    public static List<OtherItem> listOf(@Nullable List<CandidateVO> candidates, @NonNull String differenceLabel,
                                         @NonNull Collection<String> expandedNames) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        boolean anyExpandable = false;
        for (CandidateVO c : candidates) {
            if (c != null && hasExplanation(c)) {
                anyExpandable = true;
                break;
            }
        }
        List<OtherItem> items = new ArrayList<>(candidates.size());
        for (CandidateVO c : candidates) {
            if (c == null) {
                continue;
            }
            boolean expanded = c.getName() != null && expandedNames.contains(c.getName());
            items.add(new OtherItem(c.getName(), c.getConfidence(), c.getReason(), c.getDifference(),
                    differenceLabel, expanded, anyExpandable));
        }
        return items;
    }

    /** 列表中是否有可展开的行（决定是否显示「点按查看判断依据」） */
    public static boolean anyExpandable(@Nullable List<OtherItem> items) {
        if (items != null) {
            for (OtherItem item : items) {
                if (item.isExpandable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 候选是否带有判断依据（reason 或 difference 不为空白） */
    public static boolean hasExplanation(@NonNull CandidateVO candidate) {
        return trimToNull(candidate.getReason()) != null || trimToNull(candidate.getDifference()) != null;
    }

    @Nullable
    private static String trimToNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    public String getName() {
        return name;
    }

    public double getConfidence() {
        return confidence;
    }

    /** 为什么可能是它；没有时为 null */
    @Nullable
    public String getReason() {
        return reason;
    }

    /** 与最可能结果的区别；没有时为 null */
    @Nullable
    public String getDifference() {
        return difference;
    }

    @NonNull
    public String getDifferenceLabel() {
        return differenceLabel;
    }

    /** 有 reason 或 difference 时可展开 */
    public boolean isExpandable() {
        return reason != null || difference != null;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public boolean isReserveChevron() {
        return reserveChevron;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OtherItem that = (OtherItem) o;
        return Double.compare(that.confidence, confidence) == 0
                && expanded == that.expanded
                && reserveChevron == that.reserveChevron
                && Objects.equals(name, that.name)
                && Objects.equals(reason, that.reason)
                && Objects.equals(difference, that.difference)
                && differenceLabel.equals(that.differenceLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, confidence, reason, difference, differenceLabel, expanded, reserveChevron);
    }

    @NonNull
    @Override
    public String toString() {
        return "OtherItem{" + name + ", " + confidence + ", expanded=" + expanded + '}';
    }
}
