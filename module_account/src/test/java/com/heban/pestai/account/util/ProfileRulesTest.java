package com.heban.pestai.account.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class ProfileRulesTest {

    @Test
    public void splitCrops_trimsAndDropsEmptyItems() {
        Set<String> crops = ProfileRules.splitCrops(" 番茄, 黄瓜,,小麦 ");
        assertEquals(Arrays.asList("番茄", "黄瓜", "小麦"), Arrays.asList(crops.toArray(new String[0])));
        assertEquals(0, ProfileRules.splitCrops(null).size());
        assertEquals(0, ProfileRules.splitCrops("").size());
    }

    @Test
    public void joinCrops_followsCandidateOrder() {
        Set<String> selected = new LinkedHashSet<>(Arrays.asList("小麦", "番茄", "黄瓜"));
        assertEquals("番茄,黄瓜,小麦", ProfileRules.joinCrops(selected));
        assertEquals("", ProfileRules.joinCrops(new LinkedHashSet<>()));
        assertEquals("番茄,其他", ProfileRules.joinCrops(new LinkedHashSet<>(Arrays.asList("其他", "番茄"))));
    }

    @Test
    public void nicknameError() {
        assertEquals(AccountTexts.NICKNAME_EMPTY, ProfileRules.nicknameError("   "));
        assertEquals(AccountTexts.NICKNAME_EMPTY, ProfileRules.nicknameError(null));
        assertNull(ProfileRules.nicknameError("老李种菜"));
        assertNull(ProfileRules.nicknameError("一二三四五六七八九十一二三四五六"));            // 16 字
        assertEquals(AccountTexts.NICKNAME_TOO_LONG, ProfileRules.nicknameError("一二三四五六七八九十一二三四五六七"));
    }

    @Test
    public void normalizeIdentity_defaultsToFarmer() {
        assertEquals("种植户", ProfileRules.normalizeIdentity(null));
        assertEquals("种植户", ProfileRules.normalizeIdentity("其他"));
        assertEquals("农技人员", ProfileRules.normalizeIdentity("农技人员"));
    }

    @Test
    public void texts() {
        assertEquals("你主要种什么？（已选 3 项，可多选）", AccountTexts.cropTitle(3));
        assertEquals("验证码已发送至 138****5678，5 分钟内有效", AccountTexts.sentHintLong("13800005678"));
        assertEquals("已发送至 138****5678", AccountTexts.sentHintShort("138 0000 5678"));
        assertEquals("AI 病虫害识别 · v1.0.0", AccountTexts.version("1.0.0"));
        assertEquals("今后请使用 139****1111 登录，已为你自动登录", AccountTexts.rebindSuccess("139 0000 1111"));
        assertEquals("验证当前手机号 138****5678", AccountTexts.verifyCurrentPhone("13800005678"));
        assertEquals("验证当前手机号", AccountTexts.verifyCurrentPhone(null));
        assertEquals("当前状态：已生成（2026年10月13日）",
                AccountTexts.recoveryStatusGenerated(LocalDate.of(2026, 10, 13)));
        assertEquals("当前状态：已生成（2026年3月5日）", AccountTexts.recoveryStatusGenerated(LocalDate.of(2026, 3, 5)));
        assertEquals("当前状态：已生成", AccountTexts.recoveryStatusGenerated(null));
        assertEquals("该账号已停用", AccountTexts.ACCOUNT_DISABLED);
    }
}
