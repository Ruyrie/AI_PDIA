package com.heban.pestai.mine.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.mine.testutil.Fakes;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class RecoveryReminderRulesTest {

    private static final long NOW = 1_791_853_200_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Test
    public void shouldShow_onlyWhenKnownNotGenerated() {
        assertTrue(RecoveryReminderRules.shouldShow(Fakes.recoveryStatus(false), 0L, NOW));
        assertFalse(RecoveryReminderRules.shouldShow(Fakes.recoveryStatus(true), 0L, NOW));
        assertFalse(RecoveryReminderRules.shouldShow(null, 0L, NOW));   // 查询失败
    }

    @Test
    public void snoozed_withinSevenDaysOfDismiss() {
        assertFalse(RecoveryReminderRules.isSnoozed(0L, NOW));           // 从未关闭
        assertTrue(RecoveryReminderRules.isSnoozed(NOW, NOW));
        assertTrue(RecoveryReminderRules.isSnoozed(NOW - 7 * DAY + 1, NOW));
        assertFalse(RecoveryReminderRules.isSnoozed(NOW - 7 * DAY, NOW)); // 满 7 天
        assertFalse(RecoveryReminderRules.shouldShow(Fakes.recoveryStatus(false), NOW - DAY, NOW));
        assertTrue(RecoveryReminderRules.shouldShow(Fakes.recoveryStatus(false), NOW - 8 * DAY, NOW));
    }

    @Test
    public void clockMovedBack_notSnoozed() {
        // 关闭时刻晚于当前时间（系统时间被调回）：不再等待
        assertFalse(RecoveryReminderRules.isSnoozed(NOW + DAY, NOW));
    }
}
