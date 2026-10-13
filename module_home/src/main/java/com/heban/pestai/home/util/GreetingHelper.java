package com.heban.pestai.home.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;

/**
 * 首页问候栏：时段问候语、称呼、头像文字、日期（模块04 技术准备文档 2.8 第 1 点）。
 * 纯 Java 静态方法，当前时间由参数传入，方法内部不读取系统时间，便于 JUnit 用固定时间测试。
 */
public final class GreetingHelper {

    /** 游客问候语（不分时段） */
    public static final String GUEST_GREETING = "你好，农友";
    /** 昵称为空时的称呼 */
    public static final String DEFAULT_CALL_NAME = "农友";

    private static final String[] WEEK_DAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final int CALL_NAME_LENGTH = 2;

    private GreetingHelper() {
    }

    /** 5:00–10:59 上午好，11:00–12:59 中午好，13:00–17:59 下午好，其余时间晚上好 */
    @NonNull
    public static String period(int hour) {
        if (hour >= 5 && hour < 11) {
            return "上午好";
        }
        if (hour >= 11 && hour < 13) {
            return "中午好";
        }
        if (hour >= 13 && hour < 18) {
            return "下午好";
        }
        return "晚上好";
    }

    /**
     * 称呼：昵称去掉首尾空格后取前 2 个字（按 Unicode 码点，避免把表情符号截成半个字符）；
     * 不足 2 个字用完整昵称；为 null 或空白时为「农友」。
     */
    @NonNull
    public static String callName(@Nullable String nickname) {
        if (nickname == null) {
            return DEFAULT_CALL_NAME;
        }
        String name = nickname.trim();
        if (name.isEmpty()) {
            return DEFAULT_CALL_NAME;
        }
        if (name.codePointCount(0, name.length()) <= CALL_NAME_LENGTH) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, CALL_NAME_LENGTH));
    }

    /** 游客固定「你好，农友」；否则「时段，称呼」，如「下午好，老李」 */
    @NonNull
    public static String greeting(boolean guest, @Nullable String nickname, int hour) {
        if (guest) {
            return GUEST_GREETING;
        }
        return period(hour) + "，" + callName(nickname);
    }

    /** 头像文字：称呼的第 1 个字（按码点），如「老」；昵称为空时为「农」 */
    @NonNull
    public static String avatarText(@Nullable String nickname) {
        String name = callName(nickname);
        return name.substring(0, name.offsetByCodePoints(0, 1));
    }

    /** 「M月d日 周X」，如「10月10日 周六」 */
    @NonNull
    public static String dateText(@NonNull LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日 "
                + WEEK_DAYS[date.getDayOfWeek().getValue() - 1];
    }
}
