package com.heban.pestai.common.format;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * 识别时间格式化（公共数据 05「八、显示格式规则」）。now 作为参数传入，便于单元测试。
 * <ul>
 *     <li>与 now 同一天：今天 HH:mm</li>
 *     <li>now 的前一天：昨天 HH:mm</li>
 *     <li>同一年更早：MM月dd日</li>
 *     <li>不同年份：yyyy年MM月dd日</li>
 * </ul>
 */
public final class TimeFormatter {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM月dd日", Locale.CHINA);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINA);

    private TimeFormatter() {
    }

    @NonNull
    public static String formatRecent(@Nullable Date time, @NonNull LocalDateTime now) {
        if (time == null) {
            return "";
        }
        return formatRecent(toLocal(time), now);
    }

    @NonNull
    public static String formatRecent(@NonNull LocalDateTime time, @NonNull LocalDateTime now) {
        LocalDate day = time.toLocalDate();
        LocalDate today = now.toLocalDate();
        // 只比较日期：服务器时间略快于手机（created_at 比 now 晚几分钟）仍属于同一天
        if (day.equals(today)) {
            return "今天 " + HM.format(time);
        }
        if (day.equals(today.minusDays(1))) {
            return "昨天 " + HM.format(time);
        }
        if (day.getYear() == today.getYear()) {
            return MD.format(time);
        }
        return YMD.format(time);
    }

    /** 只取时分，如识别记录页分组内显示的「14:20」 */
    @NonNull
    public static String formatTime(@Nullable Date time) {
        return time == null ? "" : HM.format(toLocal(time));
    }

    @NonNull
    public static LocalDateTime toLocal(@NonNull Date time) {
        return LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault());
    }
}
