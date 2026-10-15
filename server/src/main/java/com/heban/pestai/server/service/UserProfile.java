package com.heban.pestai.server.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.heban.pestai.server.common.Identity;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.entity.User;

/**
 * 用户画像：种植身份 + 主要作物（t_user.identity / crops），用于 AI 问答的系统提示、大家常问与识图的参考信息。
 * <p>
 * 两者都来自用户资料，进入提示词前先清洗（构造时完成）：
 * <ul>
 *   <li>身份只认种植户 / 家庭园艺 / 农技人员三种（去掉首尾空白后比较），其他值视为没有填写；</li>
 *   <li>作物按逗号（含中文逗号、顿号）拆分；换行等控制字符与标点替换为空格（只保留文字、数字和 ·-/() 等少数符号），
 *       连续空格合并，每项最多 20 个字，去掉空项与重复项，最多 10 项 —— 用户无法借作物写入多行或成句的指令。</li>
 * </ul>
 */
public record UserProfile(String identity, List<String> crops) {

    public static final int MAX_CROPS = 10;
    public static final int MAX_CROP_LENGTH = 20;

    /** 作物名中保留的符号（文字、数字之外） */
    private static final String CROP_SYMBOLS = "·-/()（）";

    private static final Set<String> IDENTITIES = Set.of(Identity.GROWER, Identity.GARDENER, Identity.TECHNICIAN);

    public static final UserProfile EMPTY = new UserProfile(null, List.of());

    public UserProfile {
        identity = sanitizeIdentity(identity);
        crops = sanitizeCrops(crops);
    }

    /** 用户不存在时为 EMPTY */
    public static UserProfile of(User user) {
        return user == null ? EMPTY : new UserProfile(user.getIdentity(), splitCrops(user.getCrops()));
    }

    /** 只取作物（大家常问、识图参考信息）：t_user.crops 原文 → 清洗后的作物列表（不为 null） */
    public static List<String> cropsOf(String crops) {
        return sanitizeCrops(splitCrops(crops));
    }

    /** 身份与作物都没有（不加用户画像段落） */
    public boolean isEmpty() {
        return identity == null && crops.isEmpty();
    }

    static String sanitizeIdentity(String identity) {
        if (identity == null) {
            return null;
        }
        String trimmed = identity.strip();
        return IDENTITIES.contains(trimmed) ? trimmed : null;
    }

    static List<String> splitCrops(String crops) {
        if (crops == null || crops.isBlank()) {
            return List.of();
        }
        return Arrays.asList(crops.split("[,，、]"));
    }

    static List<String> sanitizeCrops(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> items = new LinkedHashSet<>();
        for (String item : raw) {
            String clean = sanitizeCrop(item);
            if (!clean.isEmpty()) {
                items.add(clean);
            }
            if (items.size() == MAX_CROPS) {
                break;
            }
        }
        return List.copyOf(items);
    }

    /** 单个作物名：不允许的字符（换行、控制字符、标点、表情等）替换为空格，合并空格，按码点截到 20 个字 */
    static String sanitizeCrop(String crop) {
        if (crop == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(crop.length());
        crop.codePoints().forEach(cp -> sb.appendCodePoint(
                Character.isLetterOrDigit(cp) || CROP_SYMBOLS.indexOf(cp) >= 0 ? cp : ' '));
        String collapsed = sb.toString().replaceAll(" +", " ").strip();
        return Rules.truncate(collapsed, MAX_CROP_LENGTH).strip();
    }
}
