package com.heban.pestai.ai.voice;

import androidx.annotation.NonNull;

/**
 * 录音音量（纯 Java，便于单元测试）：一段 16 位采样的 RMS 换算为 dBFS，再把 −50 ~ −10 dBFS 线性映射到 0 ~ 1，
 * 用于录音面板的音量条。安静环境约为 0，正常说话约 0.4 ~ 0.8。
 */
public final class VoiceLevel {

    /** 低于此值按静音（0） */
    static final double FLOOR_DB = -50;
    /** 高于此值按最大（1） */
    static final double CEIL_DB = -10;

    private VoiceLevel() {
    }

    /** 前 count 个采样的音量（0 ~ 1） */
    public static float of(@NonNull short[] samples, int count) {
        return fromRms(rms(samples, count));
    }

    /** 均方根（与采样同单位，0 ~ 32768） */
    public static double rms(@NonNull short[] samples, int count) {
        int n = Math.max(0, Math.min(count, samples.length));
        if (n == 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double s = samples[i];
            sum += s * s;
        }
        return Math.sqrt(sum / n);
    }

    /** RMS → 0 ~ 1 */
    public static float fromRms(double rms) {
        if (rms <= 0) {
            return 0f;
        }
        double db = 20 * Math.log10(rms / 32768.0);
        double level = (db - FLOOR_DB) / (CEIL_DB - FLOOR_DB);
        return (float) Math.max(0, Math.min(1, level));
    }
}
