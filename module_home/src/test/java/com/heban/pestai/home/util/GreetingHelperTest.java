package com.heban.pestai.home.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalDate;

/**
 * 模块04 技术准备文档 2.10：GreetingHelper 的时段、称呼、头像文字与日期。
 */
public class GreetingHelperTest {

    @Test
    public void period_boundaries() {
        assertEquals("晚上好", GreetingHelper.period(4));   // 4:59
        assertEquals("上午好", GreetingHelper.period(5));   // 5:00
        assertEquals("上午好", GreetingHelper.period(10));  // 10:59
        assertEquals("中午好", GreetingHelper.period(11));  // 11:00
        assertEquals("中午好", GreetingHelper.period(12));  // 12:59
        assertEquals("下午好", GreetingHelper.period(13));  // 13:00
        assertEquals("下午好", GreetingHelper.period(17));  // 17:59
        assertEquals("晚上好", GreetingHelper.period(18));  // 18:00
        assertEquals("晚上好", GreetingHelper.period(0));
        assertEquals("晚上好", GreetingHelper.period(23));
    }

    @Test
    public void callName_takesFirstTwoCharacters() {
        assertEquals("老李", GreetingHelper.callName("老李种菜"));
        assertEquals("张", GreetingHelper.callName("张"));
        assertEquals("农友", GreetingHelper.callName("农友5678"));
        assertEquals("老李", GreetingHelper.callName("  老李种菜  "));
        assertEquals("李四", GreetingHelper.callName("李四"));
    }

    @Test
    public void callName_blankFallsBackToNongyou() {
        assertEquals("农友", GreetingHelper.callName(null));
        assertEquals("农友", GreetingHelper.callName(""));
        assertEquals("农友", GreetingHelper.callName("   "));
    }

    @Test
    public void callName_countsCodePointsNotChars() {
        // 表情符号由两个 char 组成，按码点截取不会截成半个字符
        String emoji = new String(Character.toChars(0x1F331)); // 🌱
        assertEquals(emoji + "老", GreetingHelper.callName(emoji + "老李"));
        assertEquals(emoji, GreetingHelper.avatarText(emoji + "老李"));
    }

    @Test
    public void greeting_guestAndUser() {
        assertEquals("你好，农友", GreetingHelper.greeting(true, "老李种菜", 14));
        assertEquals("你好，农友", GreetingHelper.greeting(true, null, 8));
        assertEquals("下午好，老李", GreetingHelper.greeting(false, "老李种菜", 14));
        assertEquals("上午好，农友", GreetingHelper.greeting(false, null, 9));
        assertEquals("晚上好，张", GreetingHelper.greeting(false, "张", 22));
    }

    @Test
    public void avatarText_firstCharacter() {
        assertEquals("老", GreetingHelper.avatarText("老李种菜"));
        assertEquals("老", GreetingHelper.avatarText(" 老李"));
        assertEquals("农", GreetingHelper.avatarText(null));
        assertEquals("农", GreetingHelper.avatarText("  "));
    }

    @Test
    public void dateText_monthDayAndWeekday() {
        assertEquals("10月10日 周六", GreetingHelper.dateText(LocalDate.of(2026, 10, 10)));
        assertEquals("10月12日 周一", GreetingHelper.dateText(LocalDate.of(2026, 10, 12)));
        assertEquals("10月11日 周日", GreetingHelper.dateText(LocalDate.of(2026, 10, 11)));
        assertEquals("1月1日 周五", GreetingHelper.dateText(LocalDate.of(2027, 1, 1)));
    }
}
