package com.heban.pestai.ai.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.format.TimeFormatter;
import com.heban.pestai.common.vo.RecordItemVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * 模块08 的显示格式化（纯 Java，便于单元测试）：匹配度百分比、最近识别卡与引用卡文字、
 * 「选择要追问的识别记录」列表文字、历史对话时间、问题截断。
 */
public final class AiFormat {

    public static final String SEPARATOR = " · ";
    public static final String YESTERDAY = "昨天";

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM月dd日", Locale.CHINA);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINA);

    private AiFormat() {
    }

    /** 0.92 → 92%；为空返回空串 */
    @NonNull
    public static String percent(@Nullable Double confidence) {
        if (confidence == null) {
            return "";
        }
        return Math.round(confidence * 100) + "%";
    }

    /** 「最近识别 · 今天 14:20」 */
    @NonNull
    public static String latestLabel(@NonNull RecordItemVO record, @NonNull LocalDateTime now) {
        String time = TimeFormatter.formatRecent(record.getCreatedAt(), now);
        return time.isEmpty() ? "最近识别" : "最近识别" + SEPARATOR + time;
    }

    /** 「黄瓜霜霉病 · 匹配度 92%」 */
    @NonNull
    public static String latestTitle(@NonNull RecordItemVO record) {
        String name = nameOf(record);
        String percent = percent(record.getConfidence());
        return percent.isEmpty() ? name : name + SEPARATOR + "匹配度 " + percent;
    }

    /** 引用卡「黄瓜霜霉病 · 92% · 中度」，缺少的部分省略 */
    @NonNull
    public static String quoteTitle(@NonNull RecordItemVO record) {
        StringBuilder sb = new StringBuilder(nameOf(record));
        String percent = percent(record.getConfidence());
        if (!percent.isEmpty()) {
            sb.append(SEPARATOR).append(percent);
        }
        if (!isBlank(record.getSeverity())) {
            sb.append(SEPARATOR).append(record.getSeverity().trim());
        }
        return sb.toString();
    }

    /** 识别记录选择列表的名称：最可能结果，为空时「识别结果」 */
    @NonNull
    public static String recordName(@NonNull RecordItemVO record) {
        return nameOf(record);
    }

    /** 识别记录选择列表的第二行「病虫害 · 匹配度 92% · 今天 14:20」，缺少的部分省略 */
    @NonNull
    public static String recordSubtitle(@NonNull RecordItemVO record, @NonNull LocalDateTime now) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, Mode.text(record.getMode()));
        String percent = percent(record.getConfidence());
        appendPart(sb, percent.isEmpty() ? null : "匹配度 " + percent);
        appendPart(sb, TimeFormatter.formatRecent(record.getCreatedAt(), now));
        return sb.toString();
    }

    /**
     * 历史对话列表右上角的时间：今天「HH:mm」、昨天「昨天」、同一年「MM月dd日」、其他年份「yyyy年MM月dd日」。
     * 比 now 还晚（服务器时间略快）按今天处理。
     */
    @NonNull
    public static String sessionTime(@Nullable Date time, @NonNull LocalDateTime now) {
        if (time == null) {
            return "";
        }
        LocalDateTime local = TimeFormatter.toLocal(time);
        LocalDate day = local.toLocalDate();
        LocalDate today = now.toLocalDate();
        if (!day.isBefore(today)) {
            return HM.format(local);
        }
        if (day.equals(today.minusDays(1))) {
            return YESTERDAY;
        }
        if (day.getYear() == today.getYear()) {
            return MD.format(local);
        }
        return YMD.format(local);
    }

    private static void appendPart(@NonNull StringBuilder sb, @Nullable String part) {
        if (isBlank(part)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(SEPARATOR);
        }
        sb.append(part.trim());
    }

    @NonNull
    private static String nameOf(@NonNull RecordItemVO record) {
        return isBlank(record.getTopName()) ? "识别结果" : record.getTopName().trim();
    }

    public static boolean isBlank(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }

    @NonNull
    public static String trim(@Nullable String text) {
        return text == null ? "" : text.trim();
    }

    /** 逐字显示时取前 end 个字符的安全位置：end 落在代理对（emoji）中间时少取一个，并限制在 [0, length] */
    public static int safeEnd(@NonNull CharSequence text, int end) {
        int e = Math.max(0, Math.min(end, text.length()));
        if (e > 0 && e < text.length() && Character.isHighSurrogate(text.charAt(e - 1))) {
            e--;
        }
        return e;
    }

    /** 截断为不超过 max 个字符，不拆开代理对（emoji） */
    @NonNull
    public static String truncate(@NonNull String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
