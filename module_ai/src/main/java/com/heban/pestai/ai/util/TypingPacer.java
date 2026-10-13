package com.heban.pestai.ai.util;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * 平滑逐字输出的节奏计算（纯 Java，便于单元测试）。一段文字（思考内容或正文）对应一个实例：
 * 网络片段到达时 {@link #setTarget(int)} 更新「已收到的字数」，每帧 {@link #step(double, long)} 推进「已显示的字数」。
 * <ul>
 *     <li>基础速度 baseCps（正文约 35 字 / 秒，思考内容更快）；</li>
 *     <li>积压较多时加速：每次收到片段按「积压字数 ÷ 0.6 秒」重新计算速度（不低于基础速度），
 *     速度在两次片段之间保持不变，因此积压在约 0.6 秒内追平；追平后恢复基础速度；</li>
 *     <li>{@link #finish()}（收到 [DONE]、停止或出错）后剩余内容在 0.3 秒内显示完；</li>
 *     <li>单帧时长最多按 0.1 秒计算，卡顿后不会一次跳出一大段；</li>
 *     <li>记录最近显示的 {@value #FADE_CHARS} 个字的显示时刻，{@link #fadeAlphas(long)} 给出淡入透明度（约 160ms 从透明到不透明）。</li>
 * </ul>
 * 只在主线程使用。
 */
public final class TypingPacer {

    /** 正文基础速度（字 / 秒） */
    public static final double CONTENT_CPS = 35;
    /** 思考内容基础速度（字 / 秒），比正文快 */
    public static final double REASONING_CPS = 80;
    /** 积压追平时间 */
    public static final double CATCH_UP_SECONDS = 0.6;
    /** 结束后显示完剩余内容的时间 */
    public static final double FINISH_SECONDS = 0.3;
    /** 单帧最多推进的时长 */
    public static final double MAX_STEP_SECONDS = 0.1;
    /** 带淡入效果的最新字数 */
    public static final int FADE_CHARS = 8;
    /** 新字淡入时长 */
    public static final long FADE_NANOS = 160_000_000L;

    private static final float[] NO_FADE = new float[0];
    /** 标记「不需要淡入」的显示时刻（足够早，透明度为 1） */
    private static final long OPAQUE = Long.MIN_VALUE / 4;

    private final double baseCps;
    /** 已显示的字数（带小数，逐帧累加） */
    private double shown;
    /** 已收到的字数 */
    private int target;
    /** 当前速度（字 / 秒） */
    private double rate;
    private boolean finished;
    /** 结束后剩余的时间预算（秒） */
    private double finishLeft;
    /** 最近 FADE_CHARS 个字的显示时刻（按字序号取模的环形缓冲） */
    private final long[] revealNanos = new long[FADE_CHARS];
    /** 最后一次有新字显示的时刻 */
    private long lastRevealNanos = OPAQUE;

    public TypingPacer(double baseCps) {
        this.baseCps = baseCps;
        this.rate = baseCps;
        Arrays.fill(revealNanos, OPAQUE);
    }

    /** 收到新片段：已收到的字数变为 length（只增不减；变少时直接截断） */
    public void setTarget(int length) {
        if (length < target) {
            target = Math.max(0, length);
            shown = Math.min(shown, target);
            return;
        }
        if (length == target) {
            return;
        }
        target = length;
        rate = speedFor(baseCps, target - shown);
    }

    /** 输出结束：剩余内容在 {@link #FINISH_SECONDS} 内显示完（重复调用不重新计时） */
    public void finish() {
        if (!finished) {
            finished = true;
            finishLeft = FINISH_SECONDS;
        }
    }

    /** 立即显示全部已收到的内容，不带淡入（如正文开始后折叠的思考内容） */
    public void skipToEnd() {
        shown = target;
        rate = baseCps;
        Arrays.fill(revealNanos, OPAQUE);
        lastRevealNanos = OPAQUE;
    }

    /**
     * 推进一帧。
     *
     * @param dtSeconds 距上一帧的时长（秒），超过 {@link #MAX_STEP_SECONDS} 按上限计算
     * @param nowNanos  本帧时刻（与 {@link #fadeAlphas(long)} 使用同一时间基准，如 Choreographer 帧时间）
     * @return 显示的字数是否变化
     */
    public boolean step(double dtSeconds, long nowNanos) {
        double dt = Math.max(0, Math.min(dtSeconds, MAX_STEP_SECONDS));
        int before = getShown();
        if (shown < target && dt > 0) {
            double backlog = target - shown;
            double speed = Math.max(rate, baseCps);
            if (finished) {
                // 加一点余量，避免浮点误差导致最后一帧差一个字
                if (finishLeft <= dt + 1e-6) {
                    speed = Double.POSITIVE_INFINITY;
                } else {
                    speed = Math.max(speed, backlog / finishLeft);
                }
            }
            shown = Double.isInfinite(speed) ? target : Math.min(target, shown + speed * dt);
        }
        if (finished) {
            finishLeft = Math.max(0, finishLeft - dt);
        }
        if (shown >= target) {
            shown = target;
            rate = baseCps;
        }
        int after = getShown();
        if (after > before) {
            recordReveal(before, after, nowNanos - (long) (dt * 1e9), nowNanos);
            return true;
        }
        return false;
    }

    /** 积压为 backlog 字时的速度：不低于基础速度，并保证约 {@link #CATCH_UP_SECONDS} 秒内追平 */
    public static double speedFor(double baseCps, double backlog) {
        return Math.max(baseCps, Math.max(0, backlog) / CATCH_UP_SECONDS);
    }

    /** 已显示的字数 */
    public int getShown() {
        return (int) Math.floor(shown + 1e-9);
    }

    /** 已收到的字数 */
    public int getTarget() {
        return target;
    }

    public boolean isFinished() {
        return finished;
    }

    /** 已显示全部已收到的内容 */
    public boolean isCaughtUp() {
        return getShown() >= target;
    }

    /** 当前速度（字 / 秒），测试使用 */
    public double getRate() {
        return Math.max(rate, baseCps);
    }

    /** 还有字在淡入 */
    public boolean isFading(long nowNanos) {
        return nowNanos - lastRevealNanos < FADE_NANOS;
    }

    /**
     * 最新显示的若干个字的透明度（0–1，按字序排列，最后一个为最新的字）；没有字在淡入时返回空数组。
     * 只覆盖最新的 {@link #FADE_CHARS} 个字，更早的字均不透明。
     */
    @NonNull
    public float[] fadeAlphas(long nowNanos) {
        int count = Math.min(FADE_CHARS, getShown());
        if (count == 0 || !isFading(nowNanos)) {
            return NO_FADE;
        }
        int shownInt = getShown();
        float[] alphas = new float[count];
        for (int k = 0; k < count; k++) {
            int index = shownInt - count + k;
            alphas[k] = alphaOf(nowNanos - revealNanos[index % FADE_CHARS]);
        }
        return alphas;
    }

    /** 显示了 ageNanos 纳秒的字的透明度 */
    public static float alphaOf(long ageNanos) {
        if (ageNanos <= 0) {
            return 0f;
        }
        if (ageNanos >= FADE_NANOS) {
            return 1f;
        }
        return (float) ageNanos / FADE_NANOS;
    }

    /** 本帧新显示的字 [from, to) 的显示时刻在上一帧与本帧之间均匀分布 */
    private void recordReveal(int from, int to, long startNanos, long endNanos) {
        int n = to - from;
        int first = Math.max(from, to - FADE_CHARS);
        for (int i = first; i < to; i++) {
            long t = startNanos + (endNanos - startNanos) * (i - from + 1) / n;
            revealNanos[i % FADE_CHARS] = t;
        }
        lastRevealNanos = endNanos;
    }
}
