package com.heban.pestai.mine.format;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.UserVO;

/**
 * 用户资料的显示格式化（模块09 技术准备文档 2.8「从 VO 派生界面文字」，公共数据 05「八、显示格式规则」）。
 * 纯 Java，不依赖 Android（不使用 TextUtils），便于单元测试。
 */
public final class UserFormatter {

    /** 地区各级去掉的行政区划后缀，按长度从长到短匹配 */
    private static final String[] REGION_SUFFIXES = {
            "特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区", "自治区", "省", "市"
    };

    private UserFormatter() {
    }

    /** 11 位手机号保留前 3 位与后 4 位：13800005678 → 138****5678；其他长度原样返回，空值返回空串 */
    @NonNull
    public static String maskPhone(@Nullable String phone) {
        if (phone == null) {
            return "";
        }
        String p = phone.trim();
        if (p.length() != 11) {
            return p;
        }
        return p.substring(0, 3) + "****" + p.substring(7);
    }

    /**
     * 地区简写：按空格拆分，取前两级并去掉末尾的「省」「市」等后缀；两级相同（直辖市）只保留一个。
     * 「江苏省 南京市 江宁区」→「江苏 南京」；「北京市 北京市 东城区」→「北京」；空值返回空串。
     */
    @NonNull
    public static String shortRegion(@Nullable String region) {
        if (isBlank(region)) {
            return "";
        }
        String[] parts = region.trim().split("\\s+");
        String first = stripSuffix(parts[0]);
        if (parts.length == 1) {
            return first;
        }
        String second = stripSuffix(parts[1]);
        if (second.isEmpty() || second.equals(first)) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        return first + " " + second;
    }

    /** 昵称首字（按码点取，兼容表情等补充字符）；昵称为空返回空串 */
    @NonNull
    public static String avatarText(@Nullable String nickname) {
        if (isBlank(nickname)) {
            return "";
        }
        String n = nickname.trim();
        return n.substring(0, n.offsetByCodePoints(0, 1));
    }

    /** 我的页手机号 · 地区：「138****5678 · 江苏 南京」；地区为空只显示手机号，手机号为空只显示地区 */
    @NonNull
    public static String phoneRegion(@Nullable UserVO user) {
        if (user == null) {
            return "";
        }
        String phone = maskPhone(user.getPhone());
        String region = shortRegion(user.getRegion());
        if (phone.isEmpty()) {
            return region;
        }
        if (region.isEmpty()) {
            return phone;
        }
        return phone + " · " + region;
    }

    /** 昵称：为空时显示「未设置昵称」；user 为空返回空串 */
    @NonNull
    public static String displayName(@Nullable UserVO user) {
        if (user == null) {
            return "";
        }
        return isBlank(user.getNickname()) ? "未设置昵称" : user.getNickname().trim();
    }

    /** 没有可显示的头像图片与昵称首字时，用 person 图标占位 */
    public static boolean needsPlaceholderAvatar(@Nullable UserVO user) {
        return user == null || (isBlank(user.getNickname()) && isBlank(user.getAvatarUrl()));
    }

    /** 空值或只含空白字符 */
    public static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    @NonNull
    private static String stripSuffix(@NonNull String part) {
        for (String suffix : REGION_SUFFIXES) {
            if (part.length() > suffix.length() && part.endsWith(suffix)) {
                return part.substring(0, part.length() - suffix.length());
            }
        }
        return part;
    }
}
