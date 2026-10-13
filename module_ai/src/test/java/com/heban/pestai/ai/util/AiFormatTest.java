package com.heban.pestai.ai.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SuggestionVO;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

public class AiFormatTest {

    private static RecordItemVO cucumber() {
        RecordItemVO vo = new RecordItemVO();
        vo.setId(2036);
        vo.setStatus("SUCCESS");
        vo.setTopName("黄瓜霜霉病");
        vo.setConfidence(0.92);
        vo.setSeverity("中度");
        vo.setCreatedAt(Date.from(LocalDateTime.of(2026, 10, 13, 14, 20)
                .atZone(ZoneId.systemDefault()).toInstant()));
        return vo;
    }

    @Test
    public void percent() {
        assertEquals("92%", AiFormat.percent(0.92));
        assertEquals("88%", AiFormat.percent(0.875));
        assertEquals("100%", AiFormat.percent(1.0));
        assertEquals("", AiFormat.percent(null));
    }

    @Test
    public void latestLabelAndTitle() {
        RecordItemVO vo = cucumber();
        assertEquals("最近识别 · 今天 14:20", AiFormat.latestLabel(vo, LocalDateTime.of(2026, 10, 13, 18, 0)));
        assertEquals("最近识别 · 昨天 14:20", AiFormat.latestLabel(vo, LocalDateTime.of(2026, 10, 14, 8, 0)));
        assertEquals("黄瓜霜霉病 · 匹配度 92%", AiFormat.latestTitle(vo));
    }

    @Test
    public void quoteTitle() {
        RecordItemVO vo = cucumber();
        assertEquals("黄瓜霜霉病 · 92% · 中度", AiFormat.quoteTitle(vo));
        vo.setSeverity(null);
        assertEquals("黄瓜霜霉病 · 92%", AiFormat.quoteTitle(vo));
        vo.setConfidence(null);
        vo.setTopName(null);
        assertEquals("识别结果", AiFormat.quoteTitle(vo));
    }

    @Test
    public void truncate_keepsSurrogatePairs() {
        assertEquals("abc", AiFormat.truncate("abc", 5));
        assertEquals("ab", AiFormat.truncate("abcdef", 2));
        // 第 2、3 个 char 是一个 emoji，截断到 2 时不拆开代理对
        assertEquals("a", AiFormat.truncate("a🌱b", 2));
    }

    @Test
    public void blankAndTrim() {
        assertTrue(AiFormat.isBlank(null));
        assertTrue(AiFormat.isBlank("  \n"));
        assertFalse(AiFormat.isBlank(" a "));
        assertEquals("a", AiFormat.trim(" a \n"));
        assertEquals("", AiFormat.trim(null));
    }

    @Test
    public void defaultSuggestions() {
        List<SuggestionVO> list = AiConstants.defaultSuggestions();
        assertEquals(3, list.size());
        assertEquals(-1, list.get(0).getId());
        assertEquals("番茄叶片发黄卷曲是什么原因？", list.get(0).getText());
        assertEquals("白粉病和霜霉病怎么区分？", list.get(1).getText());
        assertEquals("蚜虫用什么办法防治效果好？", list.get(2).getText());
    }

    private static Date at(LocalDateTime time) {
        return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    public void recordSubtitle() {
        RecordItemVO vo = cucumber();
        vo.setMode("PEST");
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 18, 0);
        assertEquals("病虫害 · 匹配度 92% · 今天 14:20", AiFormat.recordSubtitle(vo, now));
        vo.setMode("CROP");
        vo.setConfidence(null);
        assertEquals("作物 / 杂草 · 今天 14:20", AiFormat.recordSubtitle(vo, now));
        vo.setMode(null);
        vo.setCreatedAt(null);
        assertEquals("", AiFormat.recordSubtitle(vo, now));
        assertEquals("黄瓜霜霉病", AiFormat.recordName(cucumber()));
    }

    @Test
    public void sessionTime_todayYesterdayDate() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 18, 0);
        assertEquals("14:20", AiFormat.sessionTime(at(LocalDateTime.of(2026, 10, 13, 14, 20)), now));
        assertEquals("服务器时间略快", "18:03", AiFormat.sessionTime(at(LocalDateTime.of(2026, 10, 13, 18, 3)), now));
        assertEquals("昨天", AiFormat.sessionTime(at(LocalDateTime.of(2026, 10, 12, 23, 59)), now));
        assertEquals("10月08日", AiFormat.sessionTime(at(LocalDateTime.of(2026, 10, 8, 9, 0)), now));
        assertEquals("2025年12月30日", AiFormat.sessionTime(at(LocalDateTime.of(2025, 12, 30, 9, 0)), now));
        assertEquals("", AiFormat.sessionTime(null, now));
    }

    @Test
    public void safeEnd_keepsSurrogatePairs() {
        assertEquals(0, AiFormat.safeEnd("abc", -1));
        assertEquals(3, AiFormat.safeEnd("abc", 9));
        assertEquals(2, AiFormat.safeEnd("abc", 2));
        // 「a🌱b」：第 2、3 个 char 是一个 emoji，取前 2 个时退回到 1
        assertEquals(1, AiFormat.safeEnd("a🌱b", 2));
        assertEquals(3, AiFormat.safeEnd("a🌱b", 3));
    }

    @Test
    public void questionTemplates_andDefaultImageQuestion() {
        List<String> templates = AiConstants.questionTemplates();
        assertEquals(5, templates.size());
        assertEquals("白菜软腐病怎么预防？", templates.get(3));
        assertEquals("服务器依赖这句话逐字一致", "请帮我看看这张照片里的作物有什么问题",
                AiConstants.DEFAULT_IMAGE_QUESTION);
    }
}
