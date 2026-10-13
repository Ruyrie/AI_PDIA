package com.heban.pestai.server.service;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 账号恢复码：12 位，字符集 ABCDEFGHJKLMNPQRSTUVWXYZ23456789（去掉易混的 0 / O / 1 / I），
 * 显示为 ABCD-EFGH-JKLM。库中只存规范形式（12 位大写、无分隔符）的 BCrypt 哈希；
 * 校验时忽略大小写、空格和连字符。
 */
public final class RecoveryCodes {

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 12;
    public static final int GROUP = 4;
    public static final char SEPARATOR = '-';

    private RecoveryCodes() {
    }

    /** 生成规范形式的恢复码（12 位，无分隔符）；字符逐个由 SecureRandom 均匀抽取 */
    public static String generate(SecureRandom random) {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 规范形式 → 显示形式：每 4 位用连字符分组，如 ABCD-EFGH-JKLM */
    public static String format(String code) {
        StringBuilder sb = new StringBuilder(code.length() + code.length() / GROUP);
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % GROUP == 0) {
                sb.append(SEPARATOR);
            }
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    /**
     * 用户输入 → 规范形式：去掉空白和连字符（含全角连字符、破折号等常见变体）并转大写；
     * 去掉后不是 12 位或含字符集以外的字符（如 0 / O / 1 / I）时返回 null。
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || isDash(c)) {
                continue;
            }
            sb.append(c);
        }
        String code = sb.toString().toUpperCase(Locale.ROOT);
        if (code.length() != LENGTH) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                return null;
            }
        }
        return code;
    }

    /** 连字符及输入法常见的变体：- ‐ ‑ ‒ – — － */
    private static boolean isDash(char c) {
        return c == '-' || c == '‐' || c == '‑' || c == '‒' || c == '–' || c == '—'
                || c == '－';
    }
}
