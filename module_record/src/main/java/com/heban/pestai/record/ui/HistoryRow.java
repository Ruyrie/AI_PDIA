package com.heban.pestai.record.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.constant.Severity;
import com.heban.pestai.common.format.RecordTag;
import com.heban.pestai.common.vo.RecordItemVO;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * 识别记录列表中的一行（界面模型，仅前端使用，由 RecordItemVO 映射）。
 * 4 种类型：日期标题、记录行、失败记录行、底部提示；所有显示格式化都在静态工厂中完成
 * （模块07 技术准备文档 2.8 第 2 点「HistoryRow.record() 的映射规则」）。
 * <p>equals() 比较全部显示字段，供 DiffUtil 的 areContentsTheSame() 使用；{@link #getKey()} 为稳定 key。</p>
 */
public final class HistoryRow {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_RECORD = 1;
    public static final int TYPE_FAILED = 2;
    public static final int TYPE_FOOTER = 3;

    /** 底部提示：向左滑动记录可删除 · 共 N 条 */
    public static final int FOOTER_NORMAL = 0;
    /** 底部提示：正在加载… */
    public static final int FOOTER_LOADING = 1;
    /** 底部提示：加载失败，点按重试 */
    public static final int FOOTER_FAILED = 2;

    static final String KEY_FOOTER = "footer";
    static final String SEP = " · ";
    static final String NAME_UNKNOWN = "未知结果";
    static final String NAME_FAILED = "识别失败";
    static final String NAME_IMAGE_BAD = "照片不清晰";
    static final String NO_PEST = "未发现病虫害";
    static final String MATCH_FORMAT = "匹配度 %d%%";
    static final String UNKNOWN_REASON = "未知原因";
    static final String TAP_TO_RETRY = "点按重新识别";

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);

    private final int type;
    @NonNull
    private final String key;
    @Nullable
    private final String title;
    @Nullable
    private final String subtitle;
    private final long recordId;
    @Nullable
    private final String imageUrl;
    @Nullable
    private final RecordTag tag;
    private final boolean imageBad;
    private final boolean first;
    private final boolean last;
    private final int footerState;
    private final int total;

    private HistoryRow(int type, @NonNull String key, @Nullable String title, @Nullable String subtitle,
                       long recordId, @Nullable String imageUrl, @Nullable RecordTag tag, boolean imageBad,
                       boolean first, boolean last, int footerState, int total) {
        this.type = type;
        this.key = key;
        this.title = title;
        this.subtitle = subtitle;
        this.recordId = recordId;
        this.imageUrl = imageUrl;
        this.tag = tag;
        this.imageBad = imageBad;
        this.first = first;
        this.last = last;
        this.footerState = footerState;
        this.total = total;
    }

    /**
     * 日期标题行。
     *
     * @param key        稳定 key，如 h_2026-10-13
     * @param title      今天 / 昨天 / 10月08日 / 2025年12月30日
     * @param firstGroup 是否为列表中的第一组（第一组上方不留组间距）
     */
    @NonNull
    public static HistoryRow header(@NonNull String key, @NonNull String title, boolean firstGroup) {
        return new HistoryRow(TYPE_HEADER, key, title, null, 0, null, null, false,
                firstGroup, false, FOOTER_NORMAL, 0);
    }

    /**
     * 记录行：status 为 SUCCESS 时为 TYPE_RECORD，其余（FAILED / IMAGE_BAD）为 TYPE_FAILED。
     *
     * @param first 是否为组内第一行（上圆角、不显示分割线）
     * @param last  是否为组内最后一行（下圆角）
     * @param zone  副标题中 HH:mm 使用的时区
     */
    @NonNull
    public static HistoryRow record(@NonNull RecordItemVO item, boolean first, boolean last, @NonNull ZoneId zone) {
        String key = "r_" + item.getId();
        String modeName = Mode.text(item.getMode());
        String time = item.getCreatedAt() == null ? null
                : HH_MM.format(item.getCreatedAt().toInstant().atZone(zone));
        if (RecognitionStatus.SUCCESS.equals(item.getStatus())) {
            String name = isBlank(item.getTopName()) ? NAME_UNKNOWN : item.getTopName();
            String middle;
            if (Severity.HEALTHY.equals(item.getSeverity())) {
                middle = NO_PEST;
            } else if (item.getConfidence() != null) {
                middle = String.format(Locale.CHINA, MATCH_FORMAT, percent(item.getConfidence()));
            } else {
                middle = null;
            }
            return new HistoryRow(TYPE_RECORD, key, name, join(modeName, middle, time), item.getId(),
                    item.getImageUrl(), RecordTag.of(item), false, first, last, FOOTER_NORMAL, 0);
        }
        boolean imageBad = RecognitionStatus.IMAGE_BAD.equals(item.getStatus());
        String reason = FailReason.text(item.getFailReason());
        String subtitle = join(modeName, reason == null ? UNKNOWN_REASON : reason, time, TAP_TO_RETRY);
        return new HistoryRow(TYPE_FAILED, key, imageBad ? NAME_IMAGE_BAD : NAME_FAILED, subtitle, item.getId(),
                item.getImageUrl(), RecordTag.FAILED, imageBad, first, last, FOOTER_NORMAL, 0);
    }

    /**
     * 底部提示行。
     *
     * @param footerState FOOTER_NORMAL / FOOTER_LOADING / FOOTER_FAILED
     * @param total       当前筛选下的记录总数（「共 N 条」）
     */
    @NonNull
    public static HistoryRow footer(int footerState, int total) {
        return new HistoryRow(TYPE_FOOTER, KEY_FOOTER, null, null, 0, null, null, false,
                false, false, footerState, total);
    }

    /** 匹配度 0–1 → 0–100 的整数百分比，四舍五入 */
    static int percent(double confidence) {
        long value = Math.round(confidence * 100);
        return (int) Math.max(0, Math.min(100, value));
    }

    @NonNull
    private static String join(@Nullable String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (isBlank(part)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    public int getType() {
        return type;
    }

    public boolean isRecord() {
        return type == TYPE_RECORD || type == TYPE_FAILED;
    }

    @NonNull
    public String getKey() {
        return key;
    }

    /** 日期标题文字，或记录名称 */
    @Nullable
    public String getTitle() {
        return title;
    }

    @Nullable
    public String getSubtitle() {
        return subtitle;
    }

    public long getRecordId() {
        return recordId;
    }

    @Nullable
    public String getImageUrl() {
        return imageUrl;
    }

    /** 严重程度标签，null 表示不显示 */
    @Nullable
    public RecordTag getTag() {
        return tag;
    }

    /** 失败记录是否为「照片不清晰」（缩略图位置显示 blur_on，否则 cloud_off） */
    public boolean isImageBad() {
        return imageBad;
    }

    /** 记录行：组内第一行；日期标题：列表中的第一组 */
    public boolean isFirst() {
        return first;
    }

    /** 记录行：组内最后一行 */
    public boolean isLast() {
        return last;
    }

    public int getFooterState() {
        return footerState;
    }

    public int getTotal() {
        return total;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HistoryRow that = (HistoryRow) o;
        return type == that.type
                && recordId == that.recordId
                && imageBad == that.imageBad
                && first == that.first
                && last == that.last
                && footerState == that.footerState
                && total == that.total
                && key.equals(that.key)
                && Objects.equals(title, that.title)
                && Objects.equals(subtitle, that.subtitle)
                && Objects.equals(imageUrl, that.imageUrl)
                && tag == that.tag;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, key, title, subtitle, recordId, imageUrl, tag, imageBad, first, last,
                footerState, total);
    }

    @NonNull
    @Override
    public String toString() {
        return "HistoryRow{" + key + ", " + title + ", " + subtitle + ", tag=" + tag
                + ", first=" + first + ", last=" + last + ", footer=" + footerState + "/" + total + '}';
    }
}
