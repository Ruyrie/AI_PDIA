package com.heban.pestai.ai.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 逐字输出节奏：基础速度、积压追平、结束后 0.3 秒显示完、单帧上限、新字淡入 */
public class TypingPacerTest {

    /** 30 次 / 秒的帧间隔 */
    private static final double FRAME = 1.0 / 30;
    private static final long FRAME_NANOS = 33_333_333L;

    private long now = 1_000_000_000L;

    /** 推进 seconds 秒（每帧 1/30 秒） */
    private void run(TypingPacer pacer, double seconds) {
        int frames = (int) Math.round(seconds / FRAME);
        for (int i = 0; i < frames; i++) {
            now += FRAME_NANOS;
            pacer.step(FRAME, now);
        }
    }

    @Test
    public void speedFor_baseOrCatchUp() {
        assertEquals(35, TypingPacer.speedFor(35, 0), 1e-9);
        assertEquals("积压少：基础速度", 35, TypingPacer.speedFor(35, 10), 1e-9);
        assertEquals("积压 120 字：0.6 秒追平", 200, TypingPacer.speedFor(35, 120), 1e-9);
        assertEquals(35, TypingPacer.speedFor(35, -5), 1e-9);
    }

    @Test
    public void smallBacklog_typesAtBaseSpeed() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(20);
        assertEquals(35, pacer.getRate(), 1e-9);
        run(pacer, 0.2);
        // 0.2 秒 × 35 字 / 秒 = 7 字
        assertEquals(7, pacer.getShown());
        assertFalse(pacer.isCaughtUp());
        run(pacer, 0.4);
        assertEquals("共 20 字，约 0.57 秒显示完", 20, pacer.getShown());
        assertTrue(pacer.isCaughtUp());
    }

    @Test
    public void largeBacklog_catchesUpWithinCatchUpWindow() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(300);
        assertEquals(500, pacer.getRate(), 1e-9);
        run(pacer, 0.3);
        assertEquals("匀速追赶：0.3 秒显示一半", 150, pacer.getShown(), 1);
        run(pacer, 0.3 + FRAME);
        assertEquals("约 0.6 秒追平", 300, pacer.getShown());
        assertEquals("追平后恢复基础速度", 35, pacer.getRate(), 1e-9);
    }

    @Test
    public void newChunk_recomputesSpeedFromCurrentBacklog() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(60);
        run(pacer, 0.3);
        int shown = pacer.getShown();
        pacer.setTarget(shown + 120);
        assertEquals("按当前积压 120 字重新计算：约 200 字 / 秒", 200, pacer.getRate(), 2.0);
        run(pacer, 0.6 + FRAME);
        assertTrue(pacer.isCaughtUp());
    }

    @Test
    public void finish_showsRemainingWithinFinishWindow() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(30);
        run(pacer, 0.1);
        assertTrue(pacer.getShown() < 30);
        pacer.finish();
        run(pacer, TypingPacer.FINISH_SECONDS);
        assertEquals("收到 [DONE] 后 0.3 秒内显示完", 30, pacer.getShown());
        assertTrue(pacer.isFinished());
    }

    @Test
    public void finish_largeBacklog_stillWithinFinishWindow() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(1000);
        pacer.finish();
        run(pacer, 0.2);
        assertTrue(pacer.getShown() < 1000);
        run(pacer, 0.1 + FRAME);
        assertEquals(1000, pacer.getShown());
    }

    @Test
    public void step_clampsLongFrames_andIgnoresZeroDt() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(10);
        assertFalse(pacer.step(0, now));
        assertEquals(0, pacer.getShown());
        // 卡顿 2 秒只按 0.1 秒推进：0.1 × 35 = 3.5 字
        assertTrue(pacer.step(2.0, now + 2_000_000_000L));
        assertEquals(3, pacer.getShown());
    }

    @Test
    public void skipToEnd_showsAllWithoutFade() {
        TypingPacer pacer = new TypingPacer(TypingPacer.REASONING_CPS);
        pacer.setTarget(50);
        run(pacer, 0.1);
        pacer.skipToEnd();
        assertEquals(50, pacer.getShown());
        assertFalse(pacer.isFading(now));
        assertEquals(0, pacer.fadeAlphas(now).length);
    }

    @Test
    public void setTarget_shrink_truncatesShown() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(10);
        run(pacer, 1);
        pacer.setTarget(4);
        assertEquals(4, pacer.getShown());
        assertEquals(4, pacer.getTarget());
    }

    @Test
    public void reasoningIsFasterThanContent() {
        assertTrue(TypingPacer.REASONING_CPS > TypingPacer.CONTENT_CPS);
    }

    // ==================== 新字淡入 ====================

    @Test
    public void alphaOf_linearOver160ms() {
        assertEquals(0f, TypingPacer.alphaOf(0), 1e-6);
        assertEquals(0.5f, TypingPacer.alphaOf(80_000_000L), 1e-6);
        assertEquals(1f, TypingPacer.alphaOf(160_000_000L), 1e-6);
        assertEquals(1f, TypingPacer.alphaOf(1_000_000_000L), 1e-6);
    }

    @Test
    public void fadeAlphas_newestCharsFadeIn_atMostEight() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(300);
        // 一帧显示约 16 字（500 字 / 秒）
        now += FRAME_NANOS;
        pacer.step(FRAME, now);
        int shown = pacer.getShown();
        assertTrue(shown > TypingPacer.FADE_CHARS);
        float[] alphas = pacer.fadeAlphas(now);
        assertEquals("最多 8 个字带淡入", TypingPacer.FADE_CHARS, alphas.length);
        assertEquals("最新的字刚出现：完全透明", 0f, alphas[alphas.length - 1], 1e-6);
        for (int i = 1; i < alphas.length; i++) {
            assertTrue("越新越透明", alphas[i] <= alphas[i - 1]);
        }
        assertTrue(pacer.isFading(now));

        // 160ms 后全部不透明
        long later = now + TypingPacer.FADE_NANOS;
        assertFalse(pacer.isFading(later));
        assertEquals(0, pacer.fadeAlphas(later).length);
    }

    @Test
    public void fadeAlphas_fewChars() {
        TypingPacer pacer = new TypingPacer(TypingPacer.CONTENT_CPS);
        pacer.setTarget(2);
        run(pacer, 0.1);
        assertEquals(2, pacer.getShown());
        float[] alphas = pacer.fadeAlphas(now);
        assertEquals(2, alphas.length);
        float[] later = pacer.fadeAlphas(now + 40_000_000L);
        assertTrue(later[1] > alphas[1]);
        assertArrayEquals(new float[0], pacer.fadeAlphas(now + 200_000_000L), 0f);
    }
}
