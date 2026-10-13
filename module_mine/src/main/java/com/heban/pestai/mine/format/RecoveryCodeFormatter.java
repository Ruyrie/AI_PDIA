package com.heban.pestai.mine.format;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.RecoveryCodeVO;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * M02「账号恢复码」行的副标题（《找回账号与恢复码》五、M02）。纯 Java，today 作为参数传入，便于单元测试。
 * <ul>
 *     <li>状态未知（未查询到 / 查询失败）：「手机号停用又忘记密码时用于找回账号」；</li>
 *     <li>未生成：「未生成 · 手机号停用又忘记密码时用于找回账号」（页面另显示 error 色小圆点）；</li>
 *     <li>已生成：「已生成 · 10月13日」，不是今年时带年份「已生成 · 2025年10月13日」。</li>
 * </ul>
 */
public final class RecoveryCodeFormatter {

    public static final String PURPOSE = "手机号停用又忘记密码时用于找回账号";
    public static final String NOT_GENERATED = "未生成 · " + PURPOSE;
    public static final String GENERATED = "已生成";

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA);

    private RecoveryCodeFormatter() {
    }

    @NonNull
    public static String subtitle(@Nullable RecoveryCodeVO status, @NonNull LocalDate today) {
        if (status == null) {
            return PURPOSE;
        }
        if (!status.isGenerated()) {
            return NOT_GENERATED;
        }
        LocalDate created = toLocalDate(status.getCreatedAt());
        if (created == null) {
            return GENERATED;
        }
        return GENERATED + " · " + (created.getYear() == today.getYear() ? MD : YMD).format(created);
    }

    /** 已明确未生成（显示小圆点提示）；状态未知时不提示 */
    public static boolean isMissing(@Nullable RecoveryCodeVO status) {
        return status != null && !status.isGenerated();
    }

    @Nullable
    private static LocalDate toLocalDate(@Nullable Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
