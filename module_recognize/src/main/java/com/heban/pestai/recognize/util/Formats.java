package com.heban.pestai.recognize.util;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.recognize.R;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 识别结果的显示格式化（纯 Java，便于单元测试）。VO 只放原始取值，「92%」「病虫害识别」等显示文字由这里生成。
 */
public final class Formats {

    /** model_name 为空时底部说明使用的模型名 */
    public static final String DEFAULT_MODEL_NAME = "AI";

    private Formats() {
    }

    /**
     * 匹配度 0–1 → 0–100：用 BigDecimal 按十进制乘以 100、HALF_UP 取整，再限定在 0–100。
     * 0.92 → 92，0.925 → 93，0.285 → 29（避免 0.285 × 100 = 28.499… 的二进制误差），1.2 → 100，负数 → 0。
     */
    public static int percent(@Nullable Double confidence) {
        if (confidence == null || confidence.isNaN() || confidence.isInfinite()) {
            return 0;
        }
        int value = BigDecimal.valueOf(confidence)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        return Math.max(0, Math.min(100, value));
    }

    public static int percent(double confidence) {
        return percent(Double.valueOf(confidence));
    }

    /** 0.06 →「6%」 */
    @NonNull
    public static String percentText(@Nullable Double confidence) {
        return percent(confidence) + "%";
    }

    /** 整数进度 →「72%」 */
    @NonNull
    public static String progressText(int progress) {
        return Math.max(0, Math.min(100, progress)) + "%";
    }

    /** 「其他可能」小进度条：百分比的 2 倍，上限 100（6% → 12） */
    public static int barPercent(@Nullable Double confidence) {
        return Math.min(100, percent(confidence) * 2);
    }

    /** 照片标签 / 模式标签文字：PEST「病虫害识别」，CROP「作物 / 杂草识别」 */
    @StringRes
    public static int modeLabelRes(@Nullable String mode) {
        return Mode.CROP.equals(mode) ? R.string.scan_mode_label_crop : R.string.scan_mode_label_pest;
    }

    /** 照片标签 / 模式标签图标：PEST bug_report，CROP grass */
    @DrawableRes
    public static int modeIconRes(@Nullable String mode) {
        return Mode.CROP.equals(mode)
                ? com.heban.pestai.base.R.drawable.ic_grass
                : com.heban.pestai.base.R.drawable.ic_bug_report;
    }

    /** 底部说明中的模型名，为空时为「AI」 */
    @NonNull
    public static String modelName(@Nullable String modelName) {
        return modelName == null || modelName.trim().isEmpty() ? DEFAULT_MODEL_NAME : modelName.trim();
    }

    /** 压缩前后大小：「1.2 MB → 320 KB」 */
    @NonNull
    public static String sizeChange(long originalSize, long compressedSize) {
        return ImageCompressor.formatSize(originalSize) + " → " + ImageCompressor.formatSize(compressedSize);
    }
}
