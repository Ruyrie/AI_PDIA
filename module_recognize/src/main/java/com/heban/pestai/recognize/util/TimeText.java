package com.heban.pestai.recognize.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.format.TimeFormatter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * R01 照片标签中的时间文字（与列表的 TimeFormatter 不同，更早的记录也带时分）：
 * <ul>
 *     <li>今天：今天 14:20</li>
 *     <li>昨天：昨天 09:05</li>
 *     <li>今年更早：10月08日 16:12</li>
 *     <li>往年：2025年10月08日 16:12</li>
 * </ul>
 * now 作为参数传入，便于单元测试。
 */
public final class TimeText {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA);
    private static final DateTimeFormatter YMD_HM = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm", Locale.CHINA);

    private TimeText() {
    }

    @NonNull
    public static String of(@Nullable Date time, @NonNull LocalDateTime now) {
        return time == null ? "" : of(TimeFormatter.toLocal(time), now);
    }

    @NonNull
    public static String of(@NonNull LocalDateTime time, @NonNull LocalDateTime now) {
        LocalDate day = time.toLocalDate();
        LocalDate today = now.toLocalDate();
        if (day.equals(today)) {
            return "今天 " + HM.format(time);
        }
        if (day.equals(today.minusDays(1))) {
            return "昨天 " + HM.format(time);
        }
        if (day.getYear() == today.getYear()) {
            return MD_HM.format(time);
        }
        return YMD_HM.format(time);
    }
}
