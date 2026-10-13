package com.heban.pestai.ai.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 思考区状态与计时文字 */
public class ThinkingFormatterTest {

    @Test
    public void state_thinkingUntilContentStarts() {
        assertEquals("已发送、尚无任何片段", ThinkingFormatter.THINKING, ThinkingFormatter.state(true, false, false));
        assertEquals("思考中", ThinkingFormatter.THINKING, ThinkingFormatter.state(true, false, true));
    }

    @Test
    public void state_afterContent() {
        assertEquals(ThinkingFormatter.DONE, ThinkingFormatter.state(true, true, true));
        assertEquals(ThinkingFormatter.DONE, ThinkingFormatter.state(false, true, true));
        assertEquals("没有思考内容（识图模型）：整行隐藏", ThinkingFormatter.HIDDEN,
                ThinkingFormatter.state(true, true, false));
        assertEquals(ThinkingFormatter.HIDDEN, ThinkingFormatter.state(false, true, false));
    }

    @Test
    public void state_stoppedBeforeContent() {
        assertEquals(ThinkingFormatter.STOPPED, ThinkingFormatter.state(false, false, true));
        assertEquals("既没有正文也没有思考内容", ThinkingFormatter.HIDDEN, ThinkingFormatter.state(false, false, false));
    }

    @Test
    public void collapsible() {
        assertTrue(ThinkingFormatter.isCollapsible(ThinkingFormatter.DONE));
        assertTrue(ThinkingFormatter.isCollapsible(ThinkingFormatter.STOPPED));
        assertFalse(ThinkingFormatter.isCollapsible(ThinkingFormatter.THINKING));
        assertFalse(ThinkingFormatter.isCollapsible(ThinkingFormatter.HIDDEN));
    }

    @Test
    public void title() {
        assertEquals("正在思考…", ThinkingFormatter.title(ThinkingFormatter.THINKING, null));
        assertEquals("已思考 12 秒", ThinkingFormatter.title(ThinkingFormatter.DONE, 12_400L));
        assertEquals("已思考 13 秒", ThinkingFormatter.title(ThinkingFormatter.DONE, 12_500L));
        assertEquals("至少 1 秒", "已思考 1 秒", ThinkingFormatter.title(ThinkingFormatter.DONE, 300L));
        assertEquals("已思考 1 秒", ThinkingFormatter.title(ThinkingFormatter.DONE, 0L));
        assertEquals("没有耗时（历史消息 reasoning_ms 为空）", "已思考",
                ThinkingFormatter.title(ThinkingFormatter.DONE, null));
        assertEquals("已停止思考", ThinkingFormatter.title(ThinkingFormatter.STOPPED, 5_000L));
        assertEquals("", ThinkingFormatter.title(ThinkingFormatter.HIDDEN, null));
    }

    @Test
    public void seconds_roundsAndAtLeastOne() {
        assertEquals(1, ThinkingFormatter.seconds(0));
        assertEquals(1, ThinkingFormatter.seconds(1_499));
        assertEquals(2, ThinkingFormatter.seconds(1_500));
        assertEquals(60, ThinkingFormatter.seconds(59_600));
    }

    @Test
    public void elapsed_floorSeconds() {
        assertEquals("已用 0 秒", ThinkingFormatter.elapsed(0));
        assertEquals("已用 0 秒", ThinkingFormatter.elapsed(999));
        assertEquals("已用 8 秒", ThinkingFormatter.elapsed(8_700));
        assertEquals("时钟回拨不出现负数", "已用 0 秒", ThinkingFormatter.elapsed(-1_000));
    }
}
