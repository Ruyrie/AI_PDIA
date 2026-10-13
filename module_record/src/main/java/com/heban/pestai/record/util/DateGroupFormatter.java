package com.heban.pestai.record.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * 识别记录日期分组标题（模块07 技术准备文档 2.7.3、2.8 第 2 点）。纯 Java，today 作为参数传入，便于单元测试。
 * <ul>
 *     <li>与 today 相同：今天</li>
 *     <li>today 的前一天：昨天</li>
 *     <li>与 today 同一年：MM月dd日（如 10月08日）</li>
 *     <li>不同年：yyyy年MM月dd日（如 2025年12月30日）</li>
 * </ul>
 */
public final class DateGroupFormatter {

    public static final String TODAY = "今天";
    public static final String YESTERDAY = "昨天";

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM月dd日", Locale.CHINA);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINA);

    private DateGroupFormatter() {
    }

    @NonNull
    public static String format(@NonNull LocalDate day, @NonNull LocalDate today) {
        if (day.equals(today)) {
            return TODAY;
        }
        if (day.equals(today.minusDays(1))) {
            return YESTERDAY;
        }
        if (day.getYear() == today.getYear()) {
            return MONTH_DAY.format(day);
        }
        return FULL_DATE.format(day);
    }

    /** created_at 按指定时区（通常为系统时区）换算为本地日期；null 返回 null */
    @Nullable
    public static LocalDate toLocalDate(@Nullable Date time, @NonNull ZoneId zone) {
        return time == null ? null : time.toInstant().atZone(zone).toLocalDate();
    }
}
