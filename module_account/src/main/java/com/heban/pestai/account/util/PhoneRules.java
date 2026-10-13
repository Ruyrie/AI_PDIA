package com.heban.pestai.account.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 手机号规则（纯 Java，可单元测试）：11 位数字、首位为 1；允许用户按 3-4-4 用空格分隔输入。
 * 模块02 登录注册、模块03 找回密码 / 找回账号共用。
 */
public final class PhoneRules {

    public static final int LENGTH = 11;
    /** 带 3-4-4 空格分隔时的最大输入长度 */
    public static final int MAX_INPUT_LENGTH = 13;

    private PhoneRules() {
    }

    /** 去掉空格与连字符；null 返回空串 */
    @NonNull
    public static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != ' ' && c != '-' && c != ' ') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 去空格后为 11 位数字且首位为 1 */
    public static boolean isValid(@Nullable String raw) {
        String phone = normalize(raw);
        if (phone.length() != LENGTH || phone.charAt(0) != '1') {
            return false;
        }
        return isAllDigits(phone);
    }

    /**
     * 输入过程中是否已经可以确定不合法（用于输入框下方实时提示，避免用户刚输入几位就报错）：
     * 含非数字字符、首位不是 1，或已满 11 位仍不合法。
     */
    public static boolean isDefinitelyInvalid(@Nullable String raw) {
        String phone = normalize(raw);
        if (phone.isEmpty()) {
            return false;
        }
        if (!isAllDigits(phone) || phone.charAt(0) != '1') {
            return true;
        }
        return phone.length() >= LENGTH && !isValid(phone);
    }

    /** 两个号码去空格后是否相同（空号码不算相同） */
    public static boolean same(@Nullable String a, @Nullable String b) {
        String x = normalize(a);
        return !x.isEmpty() && x.equals(normalize(b));
    }

    /** 脱敏：保留前 3 位和后 4 位，如 138****5678；不足 7 位原样返回 */
    @NonNull
    public static String mask(@Nullable String raw) {
        String phone = normalize(raw);
        if (phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 按 3-4-4 分隔：13800005678 → 138 0000 5678；只保留数字，超过 11 位截断 */
    @NonNull
    public static String format(@Nullable String raw) {
        String digits = digitsOf(raw);
        if (digits.length() > LENGTH) {
            digits = digits.substring(0, LENGTH);
        }
        StringBuilder sb = new StringBuilder(MAX_INPUT_LENGTH);
        for (int i = 0; i < digits.length(); i++) {
            if (i == 3 || i == 7) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    /**
     * 格式化后光标位置：原文本光标之前有 digitsBefore 个数字，返回格式化文本中同样位于这些数字之后的位置。
     */
    public static int cursorAfterDigits(@NonNull String formatted, int digitsBefore) {
        if (digitsBefore <= 0) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (Character.isDigit(formatted.charAt(i))) {
                count++;
                if (count == digitsBefore) {
                    return i + 1;
                }
            }
        }
        return formatted.length();
    }

    /** 只保留数字字符 */
    @NonNull
    public static String digitsOf(@Nullable CharSequence raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 6 位数字验证码 */
    public static boolean isSmsCode(@Nullable String code) {
        return code != null && code.length() == 6 && isAllDigits(code);
    }

    private static boolean isAllDigits(@NonNull String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
