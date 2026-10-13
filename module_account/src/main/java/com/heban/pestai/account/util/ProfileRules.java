package com.heban.pestai.account.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.Identity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 完善资料（A06）用到的本地常量与纯 Java 规则：作物候选、种植身份、昵称长度、作物字符串拆分 / 拼接。
 */
public final class ProfileRules {

    public static final int NICKNAME_MAX = 16;

    /** A06 主要作物候选（10 个，按原型顺序） */
    public static final List<String> CROPS = Collections.unmodifiableList(Arrays.asList(
            "番茄", "黄瓜", "草莓", "小麦", "水稻", "玉米", "果树", "茶叶", "食用菌", "中药材"));

    /** A06 种植身份（单选） */
    public static final List<String> IDENTITIES = Collections.unmodifiableList(Arrays.asList(
            Identity.FARMER, Identity.GARDENER, Identity.TECHNICIAN));

    private ProfileRules() {
    }

    /** 按码点计算长度（表情等补充字符算 1 个字） */
    public static int length(@Nullable String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /** 昵称去首尾空格后的错误文案；合法返回 null */
    @Nullable
    public static String nicknameError(@Nullable String nickname) {
        String value = nickname == null ? "" : nickname.trim();
        if (value.isEmpty()) {
            return AccountTexts.NICKNAME_EMPTY;
        }
        if (length(value) > NICKNAME_MAX) {
            return AccountTexts.NICKNAME_TOO_LONG;
        }
        return null;
    }

    /** UserVO.crops（逗号分隔）→ 有序集合，去掉空项与首尾空格 */
    @NonNull
    public static Set<String> splitCrops(@Nullable String crops) {
        Set<String> result = new LinkedHashSet<>();
        if (crops == null) {
            return result;
        }
        for (String item : crops.split("[,，]")) {
            String crop = item.trim();
            if (!crop.isEmpty()) {
                result.add(crop);
            }
        }
        return result;
    }

    /** 已选作物按候选常量顺序排序后用逗号拼接；不在候选中的作物排在最后；一个都没选时为空串 */
    @NonNull
    public static String joinCrops(@Nullable Collection<String> selected) {
        if (selected == null || selected.isEmpty()) {
            return "";
        }
        List<String> ordered = new ArrayList<>();
        for (String crop : CROPS) {
            if (selected.contains(crop)) {
                ordered.add(crop);
            }
        }
        for (String crop : selected) {
            if (!ordered.contains(crop)) {
                ordered.add(crop);
            }
        }
        return String.join(",", ordered);
    }

    /** 身份为空或不在三项之中时默认种植户 */
    @NonNull
    public static String normalizeIdentity(@Nullable String identity) {
        return identity != null && IDENTITIES.contains(identity) ? identity : Identity.FARMER;
    }
}
