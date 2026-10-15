package com.heban.pestai.ai.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.format.TimeFormatter;
import com.heban.pestai.common.vo.RecordItemVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * 模块08 的显示格式化（纯 Java，便于单元测试）：匹配度百分比、引用卡文字、历史对话时间、问题截断。
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
