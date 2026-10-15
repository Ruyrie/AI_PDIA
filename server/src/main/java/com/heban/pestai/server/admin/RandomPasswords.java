package com.heban.pestai.server.admin;

import java.security.SecureRandom;

/** 随机密码（初始管理员密码、重置用户密码的临时密码）：字母与数字，去掉易混的 0 O 1 l I，保证同时含字母和数字 */
final class RandomPasswords {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private RandomPasswords() {
    }

    static String generate(SecureRandom random, int length) {
        if (length < 2) {
            throw new IllegalArgumentException("length >= 2");
        }
        while (true) {
            StringBuilder sb = new StringBuilder(length);
            boolean letter = false;
            boolean digit = false;
            for (int i = 0; i < length; i++) {
                char c = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
                if (Character.isDigit(c)) {
                    digit = true;
                } else {
                    letter = true;
                }
                sb.append(c);
            }
            if (letter && digit) {
                return sb.toString();
            }
        }
    }
}
