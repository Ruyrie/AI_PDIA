package com.heban.pestai.mine.util;

import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.RecoveryCodeVO;

import java.util.concurrent.TimeUnit;

/**
 * M01「你还没有保存账号恢复码」提醒卡片的显示规则（《找回账号与恢复码》六「未生成提醒」）。纯 Java，便于单元测试。
 * <ul>
 *     <li>只在状态明确为「未生成」时显示；已生成、状态查询失败（null）不显示；游客不查询（由 ViewModel 判断）；</li>
 *     <li>关闭后 7 天内不再显示；系统时间被调回到关闭之前时不再等待，照常显示。</li>
 * </ul>
 */
public final class RecoveryReminderRules {

    /** 关闭后不再显示的时长：7 天 */
    public static final long SNOOZE_MILLIS = TimeUnit.DAYS.toMillis(7);

    private RecoveryReminderRules() {
    }

    /** dismissedAt 为 0 表示从未关闭过 */
    public static boolean isSnoozed(long dismissedAt, long now) {
        if (dismissedAt <= 0) {
            return false;
        }
        long elapsed = now - dismissedAt;
        return elapsed >= 0 && elapsed < SNOOZE_MILLIS;
    }

    public static boolean shouldShow(@Nullable RecoveryCodeVO status, long dismissedAt, long now) {
        return status != null && !status.isGenerated() && !isSnoozed(dismissedAt, now);
    }
}
