package com.heban.pestai.recognize.util;

import static org.junit.Assert.assertEquals;

import com.heban.pestai.recognize.R;

import org.junit.Test;

public class FormatsTest {

    @Test
    public void percent_roundsHalfUpInDecimal() {
        assertEquals(92, Formats.percent(0.92));
        assertEquals(93, Formats.percent(0.925));
        // 0.285 × 100 在二进制下是 28.499…，按十进制取整应为 29
        assertEquals(29, Formats.percent(0.285));
        assertEquals(6, Formats.percent(0.06));
    }

    @Test
    public void percent_clampsAndHandlesInvalid() {
        assertEquals(0, Formats.percent(0.0));
        assertEquals(100, Formats.percent(1.2));
        assertEquals(0, Formats.percent(-0.3));
        assertEquals(0, Formats.percent((Double) null));
        assertEquals(0, Formats.percent(Double.NaN));
    }

    @Test
    public void percentText_andProgressText() {
        assertEquals("6%", Formats.percentText(0.06));
        assertEquals("2%", Formats.percentText(0.02));
        assertEquals("72%", Formats.progressText(72));
        assertEquals("100%", Formats.progressText(130));
        assertEquals("0%", Formats.progressText(-5));
    }

    @Test
    public void barPercent_isDoubleOfPercentAndCapped() {
        assertEquals(12, Formats.barPercent(0.06));
        assertEquals(4, Formats.barPercent(0.02));
        assertEquals(100, Formats.barPercent(0.6));
        assertEquals(0, Formats.barPercent(null));
    }

    @Test
    public void modeLabelAndIcon() {
        assertEquals(R.string.scan_mode_label_pest, Formats.modeLabelRes("PEST"));
        assertEquals(R.string.scan_mode_label_crop, Formats.modeLabelRes("CROP"));
        assertEquals(R.string.scan_mode_label_pest, Formats.modeLabelRes(null));
        assertEquals(com.heban.pestai.base.R.drawable.ic_bug_report, Formats.modeIconRes("PEST"));
        assertEquals(com.heban.pestai.base.R.drawable.ic_grass, Formats.modeIconRes("CROP"));
    }

    @Test
    public void modelName_defaultsToAi() {
        assertEquals("DeepSeek", Formats.modelName("DeepSeek"));
        assertEquals("AI", Formats.modelName(null));
        assertEquals("AI", Formats.modelName("  "));
    }

    @Test
    public void sizeChange_formatsBothSizes() {
        assertEquals("1.2 MB → 320 KB", Formats.sizeChange(1258291, 327680));
    }
}
