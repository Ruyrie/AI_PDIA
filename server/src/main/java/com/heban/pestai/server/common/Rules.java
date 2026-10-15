package com.heban.pestai.server.common;

import java.util.regex.Pattern;

/**
 * 参数规则：与 App 端 PhoneRules / PasswordRules 一致，后端再校验一次，防止绕过 App 直接调用接口。
 */
public final class Rules {

    /** 以 1 开头的 11 位数字 */
    public static final String PHONE_REGEX = "^1\\d{10}$";
    public static final String PHONE_MESSAGE = "手机号格式不正确";

    /** 6 位数字验证码 */
    public static final String SMS_CODE_REGEX = "^\\d{6}$";
    public static final String SMS_CODE_MESSAGE = "验证码为 6 位数字";

    /** 8–20 位，同时包含字母和数字 */
    public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*\\d).{8,20}$";
    public static final String PASSWORD_MESSAGE = "密码需为 8–20 位，并同时包含字母和数字";

    /** AI 问答一条消息最多带 9 张图片（与 App 端选图上限一致） */
    public static final int CHAT_IMAGES_MAX = 9;
    public static final String CHAT_IMAGES_MESSAGE = "最多上传 9 张图片";

    private static final Pattern PHONE = Pattern.compile(PHONE_REGEX);
    private static final Pattern PASSWORD = Pattern.compile(PASSWORD_REGEX);

    private Rules() {
    }

    public static boolean isValidPhone(String phone) {
        return phone != null && PHONE.matcher(phone).matches();
    }

    public static boolean isValidPassword(String password) {
        return password != null && PASSWORD.matcher(password).matches();
    }

    /** 按 Unicode 码点截断，避免把表情等补充字符截成半个 */
    public static String truncate(String text, int maxCodePoints) {
        if (text == null) {
            return null;
        }
        int count = text.codePointCount(0, text.length());
        if (count <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
