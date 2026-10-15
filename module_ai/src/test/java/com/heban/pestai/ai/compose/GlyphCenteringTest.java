package com.heban.pestai.ai.compose;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 单行输入框按实际字形居中的位移计算 */
public class GlyphCenteringTest {

    @Test
    public void glyphAlreadyCentered_noShift() {
        // 行框 0–40，中心 20；基线 30，字形 -20..0 → 字形中心 30 - 10 = 20
        assertEquals(0, GlyphCentering.shift(0, 40, 30, -20, 0, 8));
    }

    @Test
    public void glyphDrawnHigh_movesDown() {
        // 字体下方留白多（descent 大）：基线 26，字形 -22..2 → 中心 16，比行框中心高 4 → 下移 4
        assertEquals(4, GlyphCentering.shift(0, 40, 26, -22, 2, 8));
    }

    @Test
    public void glyphDrawnLow_movesUp() {
        // 字体上方留白多：基线 34，字形 -22..2 → 中心 24，比行框中心低 4 → 上移 4
        assertEquals(-4, GlyphCentering.shift(0, 40, 34, -22, 2, 8));
    }

    @Test
    public void lineTopOffset_isRespected() {
        // 行框不从 0 开始时按相对位置计算：行框 10–50，基线 36，字形 -22..2 → 相对中心 16 → 下移 4
        assertEquals(4, GlyphCentering.shift(10, 50, 36, -22, 2, 8));
    }

    @Test
    public void shiftIsClampedToPadding() {
        assertEquals(8, GlyphCentering.shift(0, 100, 20, -10, 0, 8));
        assertEquals(-8, GlyphCentering.shift(0, 100, 90, -10, 0, 8));
    }

    @Test
    public void invalidInput_noShift() {
        assertEquals(0, GlyphCentering.shift(0, 0, 0, -10, 0, 8));
        assertEquals(0, GlyphCentering.shift(0, 40, 30, 0, 0, 8));
        assertEquals(0, GlyphCentering.shift(0, 40, 26, -22, 2, 0));
    }
}
