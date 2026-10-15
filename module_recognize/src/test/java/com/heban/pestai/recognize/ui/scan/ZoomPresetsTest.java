package com.heban.pestai.recognize.ui.scan;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ZoomPresetsTest {

    private static final float DELTA = 0.0001f;

    // ---------- 档位 ----------

    @Test
    public void of_ultraWideAndTele() {
        assertEquals(Arrays.asList(0.5f, 1f, 2f, 5f), ZoomPresets.of(0.5f, 10f));
    }

    @Test
    public void of_ultraWideOnlyWhenMinAtMost0_7() {
        assertEquals("最小 0.7 仍算超广角", Arrays.asList(0.7f, 1f, 2f), ZoomPresets.of(0.7f, 4f));
        assertEquals(Arrays.asList(1f, 2f), ZoomPresets.of(0.75f, 4f));
        assertEquals("最小 0.6 时超广角档取设备最小倍数", Arrays.asList(0.6f, 1f), ZoomPresets.of(0.6f, 1.9f));
        assertEquals("最小倍数比 0.5 还小时仍取 0.5", Arrays.asList(0.5f, 1f), ZoomPresets.of(0.4f, 1f));
    }

    @Test
    public void of_teleThresholds() {
        assertEquals(Arrays.asList(1f, 2f), ZoomPresets.of(1f, 4.9f));
        assertEquals(Arrays.asList(1f, 2f, 5f), ZoomPresets.of(1f, 5f));
        assertEquals(Arrays.asList(1f), ZoomPresets.of(1f, 1.99f));
    }

    @Test
    public void of_fixedLensHasSinglePreset() {
        assertEquals(Collections.singletonList(1f), ZoomPresets.of(1f, 1f));
    }

    @Test
    public void of_abnormalRangeStillHasOnePreset() {
        assertEquals(Collections.singletonList(2f), ZoomPresets.of(1.5f, 3f));
        assertEquals("没有任何档位时取最小倍数", Collections.singletonList(1.5f), ZoomPresets.of(1.5f, 1.8f));
    }

    // ---------- 当前档位 ----------

    @Test
    public void activeIndex_nearestBelowOrEqual() {
        List<Float> presets = Arrays.asList(0.5f, 1f, 2f, 5f);
        assertEquals(0, ZoomPresets.activeIndex(presets, 0.5f));
        assertEquals(0, ZoomPresets.activeIndex(presets, 0.8f));
        assertEquals(1, ZoomPresets.activeIndex(presets, 1f));
        assertEquals(1, ZoomPresets.activeIndex(presets, 1.9f));
        assertEquals(2, ZoomPresets.activeIndex(presets, 2f));
        assertEquals(2, ZoomPresets.activeIndex(presets, 4.9f));
        assertEquals(3, ZoomPresets.activeIndex(presets, 5f));
        assertEquals(3, ZoomPresets.activeIndex(presets, 10f));
    }

    @Test
    public void activeIndex_usesRoundedRatio() {
        List<Float> presets = Arrays.asList(1f, 2f);
        assertEquals("1.96 显示为 2×，高亮 2× 档", 1, ZoomPresets.activeIndex(presets, 1.96f));
        assertEquals(0, ZoomPresets.activeIndex(presets, 1.94f));
        assertEquals("0.96 显示为 1×", 0, ZoomPresets.activeIndex(Arrays.asList(0.5f, 1f), 0.94f));
        assertEquals(1, ZoomPresets.activeIndex(Arrays.asList(0.5f, 1f), 0.96f));
    }

    @Test
    public void activeIndex_belowAllPresets_andEmpty() {
        assertEquals(0, ZoomPresets.activeIndex(Arrays.asList(1f, 2f), 0.6f));
        assertEquals(-1, ZoomPresets.activeIndex(Collections.emptyList(), 1f));
    }

    // ---------- 文字 ----------

    @Test
    public void format_oneDecimalWithoutTrailingZero() {
        assertEquals("1×", ZoomPresets.format(1f));
        assertEquals("2×", ZoomPresets.format(2f));
        assertEquals("5×", ZoomPresets.format(5f));
        assertEquals("2.3×", ZoomPresets.format(2.3f));
        assertEquals("2.3×", ZoomPresets.format(2.34f));
        assertEquals("2.4×", ZoomPresets.format(2.36f));
        assertEquals("2×", ZoomPresets.format(1.96f));
        assertEquals("10×", ZoomPresets.format(10f));
        assertEquals("12.5×", ZoomPresets.format(12.5f));
    }

    @Test
    public void format_belowOneHasNoTimesSign() {
        assertEquals("0.5", ZoomPresets.format(0.5f));
        assertEquals("0.7", ZoomPresets.format(0.66f));
        assertEquals("四舍五入到 1 后按 1× 显示", "1×", ZoomPresets.format(0.96f));
    }

    @Test
    public void number_forAccessibility() {
        assertEquals("2", ZoomPresets.number(2f));
        assertEquals("0.5", ZoomPresets.number(0.5f));
        assertEquals("2.3", ZoomPresets.number(2.3f));
    }

    // ---------- 夹紧与插值 ----------

    @Test
    public void clamp() {
        assertEquals(1f, ZoomPresets.clamp(0.8f, 1f, 8f), DELTA);
        assertEquals(8f, ZoomPresets.clamp(9f, 1f, 8f), DELTA);
        assertEquals(2.5f, ZoomPresets.clamp(2.5f, 1f, 8f), DELTA);
        assertEquals(0.5f, ZoomPresets.clamp(Float.NaN, 0.5f, 8f), DELTA);
    }

    @Test
    public void interpolate_geometric() {
        assertEquals(1f, ZoomPresets.interpolate(1f, 4f, 0f), DELTA);
        assertEquals(4f, ZoomPresets.interpolate(1f, 4f, 1f), DELTA);
        assertEquals("1×→4× 的一半是 2×", 2f, ZoomPresets.interpolate(1f, 4f, 0.5f), DELTA);
        assertEquals(1f, ZoomPresets.interpolate(4f, 0.25f, 0.5f), DELTA);
        assertEquals("超出 [0, 1] 时取端点", 4f, ZoomPresets.interpolate(1f, 4f, 1.2f), DELTA);
        assertEquals("非正数退回线性插值", 1f, ZoomPresets.interpolate(0f, 2f, 0.5f), DELTA);
    }
}
