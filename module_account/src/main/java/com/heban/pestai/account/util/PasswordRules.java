package com.heban.pestai.account.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 密码规则（纯 Java，可单元测试）：8–20 位，同时包含字母和数字。
 * 注册页（A05）与设置新密码页（A08）共用；后端 @Size + @Pattern 再校验一次。
 *
 * <p>强度等级：0 未输入；1 弱（规则未全部满足）；规则全部满足时，字符种类（小写、大写、数字、符号）
 * 达到 3 种或长度不少于 12 位为 3 强，否则为 2 中。
 * 例：12345678 弱、abc12345 中、Abc12345! 强、abcdef123456 强。</p>
 */
public final class PasswordRules {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 20;
    /** 规则满足时，长度达到该值即视为强 */
    public static final int STRONG_LENGTH = 12;

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_WEAK = 1;
    public static final int LEVEL_MEDIUM = 2;
    public static final int LEVEL_STRONG = 3;

    private PasswordRules() {
    }

    /** 非空且长度在 8–20 位之间 */
    public static boolean lengthOk(@Nullable String password) {
        return password != null && password.length() >= MIN_LENGTH && password.length() <= MAX_LENGTH;
    }

    /** 至少包含一个英文字母 */
    public static boolean hasLetter(@Nullable String password) {
        if (password == null) {
            return false;
        }
        for (int i = 0; i < password.length(); i++) {
            if (isLetter(password.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** 至少包含一个数字 */
    public static boolean hasDigit(@Nullable String password) {
        if (password == null) {
            return false;
        }
        for (int i = 0; i < password.length(); i++) {
            if (isDigit(password.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** 同时包含字母和数字 */
    public static boolean mixOk(@Nullable String password) {
        return hasLetter(password) && hasDigit(password);
    }

    /** 三条规则全部满足 */
    public static boolean isValid(@Nullable String password) {
        return lengthOk(password) && mixOk(password);
    }

    /** 强度等级 0–3 */
    public static int level(@Nullable String password) {
        if (password == null || password.isEmpty()) {
            return LEVEL_NONE;
        }
        if (!isValid(password)) {
            return LEVEL_WEAK;
        }
        if (charTypes(password) >= 3 || password.length() >= STRONG_LENGTH) {
            return LEVEL_STRONG;
        }
        return LEVEL_MEDIUM;
    }

    /** 「强度：弱 / 中 / 强」；未输入返回空串 */
    @NonNull
    public static String strengthText(int level) {
        switch (level) {
            case LEVEL_WEAK:
                return "强度：弱";
            case LEVEL_MEDIUM:
                return "强度：中";
            case LEVEL_STRONG:
                return "强度：强";
            default:
                return "";
        }
    }

    /** 字符种类数：小写字母、大写字母、数字、其他符号 */
    static int charTypes(@NonNull String password) {
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean other = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (isDigit(c)) {
                digit = true;
            } else {
                other = true;
            }
        }
        return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
