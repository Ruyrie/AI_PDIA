package com.heban.pestai.account.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 账号恢复码（A09 输入、A12 显示）的纯 Java 规则。
 * <ul>
 *     <li>12 位，字符集 {@link #CHARSET}（去掉易混的 0 / O / 1 / I），显示为 ABCD-EFGH-JKLM；</li>
 *     <li>输入时自动转大写、去掉空格等非字母数字字符、按 4 位插入连字符、最多 12 位有效字符；</li>
 *     <li>服务器校验时同样忽略大小写、空格和连字符，提交时用 {@link #normalize(String)} 的结果。</li>
 * </ul>
 */
public final class RecoveryCodeRules {

    public static final String CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 12;
    public static final int GROUP = 4;
    public static final char SEPARATOR = '-';
    /** 格式化后的最大长度：12 位 + 2 个连字符 */
    public static final int FORMATTED_LENGTH = LENGTH + LENGTH / GROUP - 1;

    private RecoveryCodeRules() {
    }

    /** 有效字符：大写后为 A–Z 或 0–9（是否属于 {@link #CHARSET} 由 {@link #hasInvalidChars(String)} 判断） */
    private static boolean isCodeChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 转大写，只保留字母和数字，最多 12 位：「abcd-efgh jklm」→「ABCDEFGHJKLM」；空值返回空串 */
    @NonNull
    public static String normalize(@Nullable CharSequence raw) {
        if (raw == null) {
            return "";
        }
        String upper = raw.toString().toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < upper.length() && sb.length() < LENGTH; i++) {
            char c = upper.charAt(i);
            if (isCodeChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 每 4 位插入连字符：「ABCDEFGHJKLM」→「ABCD-EFGH-JKLM」，「ABCDE」→「ABCD-E」，「ABCD」→「ABCD」（末尾不加连字符） */
    @NonNull
    public static String format(@Nullable CharSequence raw) {
        String code = normalize(raw);
        StringBuilder sb = new StringBuilder(FORMATTED_LENGTH);
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % GROUP == 0) {
                sb.append(SEPARATOR);
            }
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    /** 已输入满 12 位有效字符 */
    public static boolean isComplete(@Nullable CharSequence raw) {
        return normalize(raw).length() == LENGTH;
    }

    /** 含有恢复码字符集之外的字符（0、O、1、I） */
    public static boolean hasInvalidChars(@Nullable CharSequence raw) {
        String code = normalize(raw);
        for (int i = 0; i < code.length(); i++) {
            if (CHARSET.indexOf(code.charAt(i)) < 0) {
                return true;
            }
        }
        return false;
    }

    /** 满 12 位且全部属于字符集：可以提交 */
    public static boolean isValid(@Nullable CharSequence raw) {
        return isComplete(raw) && !hasInvalidChars(raw);
    }

    /** 光标之前的有效字符数（格式化前的文本中，用于格式化后恢复光标位置） */
    public static int countCodeChars(@Nullable CharSequence text, int end) {
        if (text == null) {
            return 0;
        }
        int limit = Math.max(0, Math.min(end, text.length()));
        return normalize(text.subSequence(0, limit)).length();
    }

    /**
     * 格式化后的文本中，第 count 个有效字符之后的位置；count 为 0 时为 0，超过总数时为末尾。
     * 落在连字符之前（如「ABCD|-E」）时保持在连字符之前，继续输入时连字符自然出现在新字符之前。
     */
    public static int cursorAfterChars(@NonNull String formatted, int count) {
        if (count <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) != SEPARATOR) {
                seen++;
                if (seen == count) {
                    return i + 1;
                }
            }
        }
        return formatted.length();
    }
}
