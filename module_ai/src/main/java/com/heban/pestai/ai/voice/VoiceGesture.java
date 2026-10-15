package com.heban.pestai.ai.voice;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * 「按住 说话」的手势与录音状态机（纯 Java，便于单元测试；时间、坐标都由调用方传入）。
 * <ul>
 *     <li>按住开始录音（{@link #start}）；手指从按下位置上移超过 {@link #CANCEL_DISTANCE_DP}dp 进入「松开 取消」，
 *     移回来恢复正常（{@link #move}）；</li>
 *     <li>松开（{@link #release}）：取消状态 → {@link Result#CANCEL}（静默丢弃）；不足 {@link #MIN_DURATION_MS} →
 *     {@link Result#TOO_SHORT}（提示「说话时间太短」）；否则 {@link Result#RECOGNIZE}；</li>
 *     <li>从 {@link #COUNTDOWN_FROM_MS} 起提示「还能说 N 秒」；到 {@link #MAX_DURATION_MS} 时 {@link #tick} 自动结束
 *     （正常状态识别；手指仍在取消区则丢弃），之后的松开忽略；</li>
 *     <li>无障碍点按模式（toggle）：第一次点按开始、第二次点按结束（{@link #release}），没有上滑取消；</li>
 *     <li>ACTION_CANCEL、页面停止、视图移除时 {@link #cancel()}；</li>
 *     <li>「按住 说话」按钮上的文字见 {@link #label(boolean)}。</li>
 * </ul>
 */
public final class VoiceGesture {

    /** 最短 1 秒，不足提示「说话时间太短」 */
    public static final long MIN_DURATION_MS = 1_000;
    /** 最长 60 秒（后端上限），到时自动结束 */
    public static final long MAX_DURATION_MS = 60_000;
    /** 50 秒起显示倒计时「还能说 N 秒」 */
    public static final long COUNTDOWN_FROM_MS = 50_000;
    /** 上滑超过 80dp 进入取消状态 */
    public static final int CANCEL_DISTANCE_DP = 80;

    /** 状态 */
    public enum State {
        /** 没有在录音（含已结束、已取消） */
        IDLE,
        /** 录音中：松开识别 */
        RECORDING,
        /** 录音中、手指在取消区：松开取消 */
        CANCEL
    }

    /** 松开 / 自动结束的处理方式 */
    public enum Result {
        /** 不需要处理（没有在录音，如自动结束后的松开） */
        NONE,
        /** 静默丢弃 */
        CANCEL,
        /** 丢弃并提示「说话时间太短」 */
        TOO_SHORT,
        /** 结束录音并上传识别 */
        RECOGNIZE
    }

    /** 录音面板下方的提示 */
    public enum Hint {
        /** 「松开 识别 · 上滑 取消」 */
        RELEASE_TO_RECOGNIZE,
        /** 无障碍点按模式：「再次点按 结束并识别」 */
        TAP_TO_STOP,
        /** 「松开 取消」（红色面板） */
        RELEASE_TO_CANCEL,
        /** 「还能说 N 秒」，N 见 {@link #remainingSeconds(long)} */
        COUNTDOWN
    }

    /** 「按住 说话」按钮上的文字（与录音浮层的 {@link Hint} 同时变化） */
    public enum Label {
        /** 没有在录音：「按住 说话」 */
        HOLD_TO_TALK,
        /** 按住录音中：「松开 识别」（按下状态，底色加深） */
        RELEASE_TO_RECOGNIZE,
        /** 手指在取消区：「松开 取消」（errorContainer 配色） */
        RELEASE_TO_CANCEL,
        /** 无障碍点按模式录音中：「结束并识别」 */
        TAP_TO_STOP,
        /** 录音已结束、正在识别：「识别中…」（按钮不可用） */
        RECOGNIZING
    }

    private final float cancelDistancePx;
    @NonNull
    private State state = State.IDLE;
    private boolean toggle;
    private long startMs;
    private float downY;

    /**
     * @param cancelDistancePx 上滑多少像素进入取消状态（{@link #CANCEL_DISTANCE_DP} 换算后的值）
     */
    public VoiceGesture(float cancelDistancePx) {
        this.cancelDistancePx = cancelDistancePx;
    }

    /**
     * 开始录音。
     *
     * @param nowMs  当前时间（毫秒，单调时钟）
     * @param downY  按下位置（屏幕坐标），点按模式忽略
     * @param toggle 无障碍点按模式（再次点按结束，没有上滑取消）
     */
    public void start(long nowMs, float downY, boolean toggle) {
        this.state = State.RECORDING;
        this.startMs = nowMs;
        this.downY = downY;
        this.toggle = toggle;
    }

    /**
     * 手指移动：上移超过阈值进入取消状态，移回阈值以内恢复。
     *
     * @return 状态是否改变（需要刷新面板）
     */
    public boolean move(float y) {
        if (!isRecording() || toggle) {
            return false;
        }
        State next = downY - y > cancelDistancePx ? State.CANCEL : State.RECORDING;
        if (next == state) {
            return false;
        }
        state = next;
        return true;
    }

    /**
     * 定时检查（约每 100ms）：到 60 秒自动结束。
     *
     * @return 自动结束时为 {@link Result#RECOGNIZE}（手指在取消区为 {@link Result#CANCEL}），否则 {@link Result#NONE}
     */
    @NonNull
    public Result tick(long nowMs) {
        if (!isRecording() || nowMs - startMs < MAX_DURATION_MS) {
            return Result.NONE;
        }
        Result result = state == State.CANCEL ? Result.CANCEL : Result.RECOGNIZE;
        state = State.IDLE;
        return result;
    }

    /** 松开手指（点按模式为第二次点按）；没有在录音（已自动结束、已取消）时返回 {@link Result#NONE} */
    @NonNull
    public Result release(long nowMs) {
        if (state == State.CANCEL) {
            state = State.IDLE;
            return Result.CANCEL;
        }
        if (state == State.RECORDING) {
            state = State.IDLE;
            return nowMs - startMs < MIN_DURATION_MS ? Result.TOO_SHORT : Result.RECOGNIZE;
        }
        return Result.NONE;
    }

    /** ACTION_CANCEL、页面停止、视图移除：直接回到 IDLE（调用方丢弃录音） */
    public void cancel() {
        state = State.IDLE;
    }

    @NonNull
    public State getState() {
        return state;
    }

    public boolean isRecording() {
        return state != State.IDLE;
    }

    /** 手指在取消区（面板变红，「松开 取消」） */
    public boolean isCancelArmed() {
        return state == State.CANCEL;
    }

    /** 无障碍点按模式 */
    public boolean isToggle() {
        return toggle;
    }

    /** 已录音时长（0 到 60 秒） */
    public long elapsedMs(long nowMs) {
        return Math.max(0, Math.min(MAX_DURATION_MS, nowMs - startMs));
    }

    /** 面板下方的提示：取消状态优先，其次 50 秒起的倒计时 */
    @NonNull
    public Hint hint(long nowMs) {
        if (state == State.CANCEL) {
            return Hint.RELEASE_TO_CANCEL;
        }
        if (elapsedMs(nowMs) >= COUNTDOWN_FROM_MS) {
            return Hint.COUNTDOWN;
        }
        return toggle ? Hint.TAP_TO_STOP : Hint.RELEASE_TO_RECOGNIZE;
    }

    /**
     * 「按住 说话」按钮上的文字：录音中按状态显示（取消区优先），没有在录音时识别中为「识别中…」，否则「按住 说话」。
     *
     * @param recognizing 录音已结束、正在写完文件或正在上传识别
     */
    @NonNull
    public Label label(boolean recognizing) {
        if (state == State.CANCEL) {
            return Label.RELEASE_TO_CANCEL;
        }
        if (state == State.RECORDING) {
            return toggle ? Label.TAP_TO_STOP : Label.RELEASE_TO_RECOGNIZE;
        }
        return recognizing ? Label.RECOGNIZING : Label.HOLD_TO_TALK;
    }

    /** 倒计时「还能说 N 秒」的 N：剩余时间向上取整（50.0 秒为 10，59.1 秒为 1） */
    public int remainingSeconds(long nowMs) {
        long left = MAX_DURATION_MS - elapsedMs(nowMs);
        return (int) ((left + 999) / 1000);
    }

    /** 计时文字：5 秒为「0:05」，60 秒为「1:00」 */
    @NonNull
    public static String formatTimer(long elapsedMs) {
        long seconds = Math.max(0, elapsedMs) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
