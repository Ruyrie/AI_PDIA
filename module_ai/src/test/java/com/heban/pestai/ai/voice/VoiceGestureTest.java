package com.heban.pestai.ai.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.ai.voice.VoiceGesture.Hint;
import com.heban.pestai.ai.voice.VoiceGesture.Label;
import com.heban.pestai.ai.voice.VoiceGesture.Result;
import com.heban.pestai.ai.voice.VoiceGesture.State;

import org.junit.Test;

/** 「按住 说话」手势状态机：上滑取消、最短 / 最长时长、倒计时、点按模式、按钮文字 */
public class VoiceGestureTest {

    /** 测试中 80dp 按 1 倍密度即 80px */
    private static final float CANCEL = 80f;
    private static final long T0 = 10_000;
    private static final float DOWN_Y = 1_500f;

    private static VoiceGesture holding() {
        VoiceGesture g = new VoiceGesture(CANCEL);
        g.start(T0, DOWN_Y, false);
        return g;
    }

    @Test
    public void initial_idle_everythingIgnored() {
        VoiceGesture g = new VoiceGesture(CANCEL);
        assertEquals(State.IDLE, g.getState());
        assertFalse(g.isRecording());
        assertFalse(g.move(0f));
        assertEquals(Result.NONE, g.tick(T0 + 70_000));
        assertEquals("没有开始时松开不处理", Result.NONE, g.release(T0));
    }

    @Test
    public void start_recording_normalHint() {
        VoiceGesture g = holding();
        assertEquals(State.RECORDING, g.getState());
        assertTrue(g.isRecording());
        assertFalse(g.isCancelArmed());
        assertFalse(g.isToggle());
        assertEquals(Hint.RELEASE_TO_RECOGNIZE, g.hint(T0));
        assertEquals(0, g.elapsedMs(T0));
        assertEquals(5_400, g.elapsedMs(T0 + 5_400));
        assertEquals("时钟回拨按 0", 0, g.elapsedMs(T0 - 100));
        assertEquals("最多 60 秒", VoiceGesture.MAX_DURATION_MS, g.elapsedMs(T0 + 90_000));
    }

    @Test
    public void release_underOneSecond_tooShort() {
        VoiceGesture g = holding();
        assertEquals(Result.TOO_SHORT, g.release(T0 + 999));
        assertEquals(State.IDLE, g.getState());
        assertEquals("已结束，再次松开不处理", Result.NONE, g.release(T0 + 2_000));

        VoiceGesture instant = holding();
        assertEquals(Result.TOO_SHORT, instant.release(T0));
    }

    @Test
    public void release_fromOneSecond_recognize() {
        VoiceGesture g = holding();
        assertEquals("正好 1 秒识别", Result.RECOGNIZE, g.release(T0 + VoiceGesture.MIN_DURATION_MS));
        VoiceGesture longer = holding();
        assertEquals(Result.RECOGNIZE, longer.release(T0 + 12_345));
        assertFalse(longer.isRecording());
    }

    @Test
    public void slideUp_beyondThreshold_cancelState_andBack() {
        VoiceGesture g = holding();
        assertFalse("上移 40px 仍正常", g.move(DOWN_Y - 40));
        assertFalse("正好 80px 不算超过", g.move(DOWN_Y - CANCEL));
        assertEquals(State.RECORDING, g.getState());

        assertTrue("超过 80px 进入取消，状态改变", g.move(DOWN_Y - CANCEL - 0.5f));
        assertEquals(State.CANCEL, g.getState());
        assertTrue(g.isCancelArmed());
        assertTrue("取消状态仍在录音", g.isRecording());
        assertEquals(Hint.RELEASE_TO_CANCEL, g.hint(T0 + 3_000));
        assertFalse("继续上移状态不变", g.move(DOWN_Y - 300));

        assertTrue("移回阈值以内恢复正常", g.move(DOWN_Y - 20));
        assertEquals(State.RECORDING, g.getState());
        assertEquals(Hint.RELEASE_TO_RECOGNIZE, g.hint(T0 + 3_000));
        assertFalse("向下移动不取消", g.move(DOWN_Y + 200));
    }

    @Test
    public void release_inCancelState_cancel_evenAfterLongRecording() {
        VoiceGesture g = holding();
        g.move(DOWN_Y - 200);
        assertEquals(Result.CANCEL, g.release(T0 + 30_000));
        assertEquals(State.IDLE, g.getState());

        VoiceGesture quick = holding();
        quick.move(DOWN_Y - 200);
        assertEquals("不足 1 秒但在取消区：静默取消，不提示太短", Result.CANCEL, quick.release(T0 + 300));
    }

    @Test
    public void countdown_fromFiftySeconds() {
        VoiceGesture g = holding();
        assertEquals(Hint.RELEASE_TO_RECOGNIZE, g.hint(T0 + 49_999));
        assertEquals(Hint.COUNTDOWN, g.hint(T0 + VoiceGesture.COUNTDOWN_FROM_MS));
        assertEquals("50.0 秒：还能说 10 秒", 10, g.remainingSeconds(T0 + 50_000));
        assertEquals(10, g.remainingSeconds(T0 + 50_999));
        assertEquals(9, g.remainingSeconds(T0 + 51_000));
        assertEquals(5, g.remainingSeconds(T0 + 55_500));
        assertEquals("59.1 秒：还能说 1 秒", 1, g.remainingSeconds(T0 + 59_100));
        assertEquals(1, g.remainingSeconds(T0 + 59_999));
        assertEquals(0, g.remainingSeconds(T0 + 60_000));
        assertEquals(60, g.remainingSeconds(T0));
    }

    @Test
    public void cancelHint_takesPrecedenceOverCountdown() {
        VoiceGesture g = holding();
        g.move(DOWN_Y - 100);
        assertEquals(Hint.RELEASE_TO_CANCEL, g.hint(T0 + 55_000));
        g.move(DOWN_Y);
        assertEquals(Hint.COUNTDOWN, g.hint(T0 + 55_000));
    }

    @Test
    public void tick_atSixtySeconds_autoStopsAndRecognizes_laterReleaseIgnored() {
        VoiceGesture g = holding();
        assertEquals(Result.NONE, g.tick(T0 + 1_000));
        assertEquals(Result.NONE, g.tick(T0 + 59_999));
        assertTrue(g.isRecording());
        assertEquals(Result.RECOGNIZE, g.tick(T0 + VoiceGesture.MAX_DURATION_MS));
        assertEquals(State.IDLE, g.getState());
        assertEquals("只自动结束一次", Result.NONE, g.tick(T0 + 61_000));
        assertEquals("之后的松开忽略", Result.NONE, g.release(T0 + 62_000));
        assertFalse("之后的移动忽略", g.move(DOWN_Y - 300));
    }

    @Test
    public void tick_atSixtySeconds_inCancelZone_discards() {
        VoiceGesture g = holding();
        g.move(DOWN_Y - 100);
        assertEquals("手指仍在取消区：到时丢弃", Result.CANCEL, g.tick(T0 + 60_500));
        assertEquals(Result.NONE, g.release(T0 + 61_000));
    }

    @Test
    public void cancel_returnsToIdle() {
        VoiceGesture g = holding();
        g.move(DOWN_Y - 100);
        g.cancel();
        assertEquals(State.IDLE, g.getState());
        assertFalse(g.isRecording());
        assertFalse(g.isCancelArmed());
        assertEquals(Result.NONE, g.release(T0 + 5_000));
        assertEquals(Result.NONE, g.tick(T0 + 70_000));
    }

    @Test
    public void toggleMode_noSlideCancel_tapToStop() {
        VoiceGesture g = new VoiceGesture(CANCEL);
        g.start(T0, 0f, true);
        assertTrue(g.isToggle());
        assertEquals(Hint.TAP_TO_STOP, g.hint(T0 + 2_000));
        assertFalse("点按模式没有上滑取消", g.move(-1_000f));
        assertEquals(State.RECORDING, g.getState());
        assertEquals(Hint.COUNTDOWN, g.hint(T0 + 52_000));
        assertEquals("第二次点按：结束并识别", Result.RECOGNIZE, g.release(T0 + 4_000));

        g.start(T0, 0f, true);
        assertEquals("两次点按间隔不足 1 秒也算太短", Result.TOO_SHORT, g.release(T0 + 500));

        g.start(T0, 0f, true);
        assertEquals(Result.RECOGNIZE, g.tick(T0 + 60_000));
    }

    @Test
    public void restart_resetsState() {
        VoiceGesture g = holding();
        g.move(DOWN_Y - 200);
        g.release(T0 + 2_000);
        g.start(T0 + 10_000, 800f, false);
        assertEquals(State.RECORDING, g.getState());
        assertFalse(g.isToggle());
        assertEquals(0, g.elapsedMs(T0 + 10_000));
        assertFalse("以新的按下位置计算", g.move(800f - 50));
        assertTrue(g.move(800f - 81));
    }

    @Test
    public void formatTimer() {
        assertEquals("0:00", VoiceGesture.formatTimer(0));
        assertEquals("0:00", VoiceGesture.formatTimer(999));
        assertEquals("0:05", VoiceGesture.formatTimer(5_400));
        assertEquals("0:59", VoiceGesture.formatTimer(59_999));
        assertEquals("1:00", VoiceGesture.formatTimer(60_000));
        assertEquals("0:00", VoiceGesture.formatTimer(-1));
    }

    // ==================== 「按住 说话」按钮文字 ====================

    @Test
    public void label_idle_holdToTalk_orRecognizing() {
        VoiceGesture g = new VoiceGesture(CANCEL);
        assertEquals(Label.HOLD_TO_TALK, g.label(false));
        assertEquals("识别中（按钮不可用）", Label.RECOGNIZING, g.label(true));
    }

    @Test
    public void label_holding_releaseToRecognize_cancelZone_back() {
        VoiceGesture g = holding();
        assertEquals("按住：松开 识别", Label.RELEASE_TO_RECOGNIZE, g.label(false));
        g.move(DOWN_Y - CANCEL - 1);
        assertEquals("取消区：松开 取消", Label.RELEASE_TO_CANCEL, g.label(false));
        assertEquals("取消区优先于其他状态", Label.RELEASE_TO_CANCEL, g.label(true));
        g.move(DOWN_Y - 10);
        assertEquals("移回来恢复", Label.RELEASE_TO_RECOGNIZE, g.label(false));

        assertEquals(Result.RECOGNIZE, g.release(T0 + 3_000));
        assertEquals("松开后正在识别", Label.RECOGNIZING, g.label(true));
        assertEquals("识别结束回到按住说话", Label.HOLD_TO_TALK, g.label(false));
    }

    @Test
    public void label_cancelOrTooShort_backToHoldToTalk() {
        VoiceGesture cancelled = holding();
        cancelled.move(DOWN_Y - CANCEL - 1);
        assertEquals(Result.CANCEL, cancelled.release(T0 + 3_000));
        assertEquals(Label.HOLD_TO_TALK, cancelled.label(false));

        VoiceGesture shortOne = holding();
        assertEquals(Result.TOO_SHORT, shortOne.release(T0 + 300));
        assertEquals(Label.HOLD_TO_TALK, shortOne.label(false));

        VoiceGesture systemCancel = holding();
        systemCancel.cancel();
        assertEquals("ACTION_CANCEL / onStop", Label.HOLD_TO_TALK, systemCancel.label(false));
    }

    @Test
    public void label_toggleMode_tapToStop() {
        VoiceGesture g = new VoiceGesture(CANCEL);
        g.start(T0, 0f, true);
        assertEquals("TalkBack 点按模式：结束并识别", Label.TAP_TO_STOP, g.label(false));
        assertFalse("点按模式没有上滑取消", g.move(-1_000f));
        assertEquals(Label.TAP_TO_STOP, g.label(false));
        assertEquals(Result.RECOGNIZE, g.release(T0 + 2_000));
        assertEquals(Label.RECOGNIZING, g.label(true));
    }

    @Test
    public void thresholds() {
        assertEquals(1_000, VoiceGesture.MIN_DURATION_MS);
        assertEquals(60_000, VoiceGesture.MAX_DURATION_MS);
        assertEquals(50_000, VoiceGesture.COUNTDOWN_FROM_MS);
        assertEquals(80, VoiceGesture.CANCEL_DISTANCE_DP);
    }

    // ==================== 音量 ====================

    @Test
    public void voiceLevel_silenceZero_fullScaleOne() {
        assertEquals(0f, VoiceLevel.of(new short[800], 800), 0f);
        assertEquals(0f, VoiceLevel.of(new short[0], 0), 0f);
        short[] loud = new short[100];
        for (int i = 0; i < loud.length; i++) {
            loud[i] = (short) (i % 2 == 0 ? Short.MAX_VALUE : -Short.MAX_VALUE);
        }
        assertEquals(1f, VoiceLevel.of(loud, loud.length), 0.001f);
        // −30 dBFS 在 −50 ~ −10 的正中间
        double rms = 32768 * Math.pow(10, -30 / 20.0);
        assertEquals(0.5f, VoiceLevel.fromRms(rms), 0.001f);
        assertEquals("−50 dBFS 以下为 0", 0f, VoiceLevel.fromRms(32768 * Math.pow(10, -60 / 20.0)), 0f);
        assertEquals("只计算前 count 个", 0f, VoiceLevel.of(new short[]{0, 0, Short.MAX_VALUE}, 2), 0f);
        assertEquals(1000.0, VoiceLevel.rms(new short[]{1000, -1000}, 2), 0.0001);
    }
}
