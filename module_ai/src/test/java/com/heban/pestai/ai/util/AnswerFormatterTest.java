package com.heban.pestai.ai.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class AnswerFormatterTest {

    private static final String SAMPLE = "结合识别结果，初步判断为 **黄瓜霜霉病（中度）**，建议 3 天内处理。\n"
            + "\n"
            + "### 处理建议\n"
            + "1. 上午通风排湿，减少叶面结露时间\n"
            + "2. 摘除病叶，带出棚外集中处理\n"
            + "3. 发病初期可选烯酰吗啉等药剂，按标签用量喷施";

    @Test
    public void parse_sample() {
        List<AnswerFormatter.Block> blocks = AnswerFormatter.parse(SAMPLE);
        assertEquals(5, blocks.size());

        AnswerFormatter.Block first = blocks.get(0);
        assertEquals(AnswerFormatter.PARAGRAPH, first.type);
        assertFalse(first.gapBefore);
        assertEquals(3, first.segments.size());
        assertFalse(first.segments.get(0).bold);
        assertTrue(first.segments.get(1).bold);
        assertEquals("黄瓜霜霉病（中度）", first.segments.get(1).text);
        assertEquals("结合识别结果，初步判断为 黄瓜霜霉病（中度），建议 3 天内处理。", first.text());

        AnswerFormatter.Block heading = blocks.get(1);
        assertEquals(AnswerFormatter.HEADING, heading.type);
        assertEquals("处理建议", heading.text());
        assertTrue(heading.gapBefore);

        AnswerFormatter.Block item1 = blocks.get(2);
        assertEquals(AnswerFormatter.LIST_ITEM, item1.type);
        assertEquals("1.", item1.label);
        assertEquals("上午通风排湿，减少叶面结露时间", item1.text());
        assertTrue("标题后的第一项留段落间距", item1.gapBefore);
        assertFalse("连续列表项之间不留间距", blocks.get(3).gapBefore);
        assertEquals("3.", blocks.get(4).label);
    }

    @Test
    public void parse_boldLineAsHeading() {
        List<AnswerFormatter.Block> blocks = AnswerFormatter.parse("**处理建议**\n**处理建议**：\n**建议今天就打药，不要拖到明天。**");
        assertEquals(AnswerFormatter.HEADING, blocks.get(0).type);
        assertEquals("处理建议", blocks.get(0).text());
        assertEquals(AnswerFormatter.HEADING, blocks.get(1).type);
        assertEquals("处理建议：", blocks.get(1).text());
        // 长句或以句号结尾的整行加粗仍是普通段落中的加粗
        assertEquals(AnswerFormatter.PARAGRAPH, blocks.get(2).type);
        assertTrue(blocks.get(2).segments.get(0).bold);
    }

    @Test
    public void parse_listVariants() {
        List<AnswerFormatter.Block> blocks = AnswerFormatter.parse("1、通风\n2）摘叶\n- 喷药\n• 复查\n1.5 公斤兑水");
        assertEquals("1.", blocks.get(0).label);
        assertEquals("通风", blocks.get(0).text());
        assertEquals("2.", blocks.get(1).label);
        assertEquals("•", blocks.get(2).label);
        assertEquals("喷药", blocks.get(2).text());
        assertEquals("•", blocks.get(3).label);
        // 小数不是编号
        assertEquals(AnswerFormatter.PARAGRAPH, blocks.get(4).type);
        assertNull(blocks.get(4).label);
    }

    @Test
    public void parse_unclosedBold_whileStreaming() {
        List<AnswerFormatter.Block> blocks = AnswerFormatter.parse("初步判断为 **黄瓜霜");
        assertEquals(1, blocks.size());
        List<AnswerFormatter.Segment> segments = blocks.get(0).segments;
        assertEquals(2, segments.size());
        assertEquals("黄瓜霜", segments.get(1).text);
        assertTrue(segments.get(1).bold);
    }

    @Test
    public void parse_emptyAndNull() {
        assertTrue(AnswerFormatter.parse(null).isEmpty());
        assertTrue(AnswerFormatter.parse("").isEmpty());
        assertTrue(AnswerFormatter.parse("\n\n  \n").isEmpty());
    }

    @Test
    public void parse_crlf() {
        List<AnswerFormatter.Block> blocks = AnswerFormatter.parse("第一段\r\n第二段");
        assertEquals(2, blocks.size());
        assertEquals("第二段", blocks.get(1).text());
    }

    @Test
    public void plainText_removesMarkers_keepsNumbersAndNewlines() {
        String expected = "结合识别结果，初步判断为 黄瓜霜霉病（中度），建议 3 天内处理。\n"
                + "\n"
                + "处理建议\n"
                + "1. 上午通风排湿，减少叶面结露时间\n"
                + "2. 摘除病叶，带出棚外集中处理\n"
                + "3. 发病初期可选烯酰吗啉等药剂，按标签用量喷施";
        assertEquals(expected, AnswerFormatter.plainText(SAMPLE));
        assertEquals("", AnswerFormatter.plainText(null));
    }

    @Test
    public void fadeTargets_skipMarkdownSymbolsAndWhitespace() {
        // 原文末尾 8 个字「霉病**，建 议」：** 与空格在渲染结果中不存在，其余按顺序对应渲染结果末尾的非空白字
        int[] targets = AnswerFormatter.fadeTargets("初步判断为 **黄瓜霜霉病**，建 议", 8);
        org.junit.Assert.assertArrayEquals(new int[]{4, 3, -1, -1, 2, 1, -1, 0}, targets);
    }

    @Test
    public void fadeTargets_shortText() {
        int[] targets = AnswerFormatter.fadeTargets("ab", 4);
        assertEquals(4, targets.length);
        assertEquals(-1, targets[0]);
        assertEquals(-1, targets[1]);
        assertEquals(1, targets[2]);
        assertEquals(0, targets[3]);
        assertEquals(0, AnswerFormatter.fadeTargets("abc", 0).length);
    }
}
