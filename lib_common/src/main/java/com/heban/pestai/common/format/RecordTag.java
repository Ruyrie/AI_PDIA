package com.heban.pestai.common.format;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.constant.Severity;
import com.heban.pestai.common.vo.RecordItemVO;

/**
 * 识别记录标签：文字 + 背景 / 文字两个主题颜色属性（公共数据 05 严重程度配色）。
 * 使用 MaterialColors.getColor(view, tag.bgAttr) 从当前主题取色，布局中不写死色值。
 *
 * <table>
 *     <tr><td>健康</td><td>PEST、SUCCESS，severity 为健康</td><td>primaryContainer / onPrimaryContainer</td></tr>
 *     <tr><td>轻度</td><td>PEST、SUCCESS，severity 为轻度</td><td>secondaryContainer / onSecondaryContainer</td></tr>
 *     <tr><td>中度</td><td>PEST、SUCCESS，severity 为中度</td><td>warningContainer / onWarningContainer</td></tr>
 *     <tr><td>较重</td><td>PEST、SUCCESS，severity 为较重</td><td>errorContainer / onErrorContainer</td></tr>
 *     <tr><td>作物</td><td>CROP、SUCCESS</td><td>tertiaryContainer / onTertiaryContainer</td></tr>
 *     <tr><td>失败</td><td>IMAGE_BAD / FAILED</td><td>surfaceContainerHighest / onSurfaceVariant</td></tr>
 * </table>
 * PEST、SUCCESS 但 severity 为空或不在四档内时 {@link #of} 返回 null，隐藏标签。
 */
public enum RecordTag {

    HEALTHY("健康", com.google.android.material.R.attr.colorPrimaryContainer,
            com.google.android.material.R.attr.colorOnPrimaryContainer),
    MILD("轻度", com.google.android.material.R.attr.colorSecondaryContainer,
            com.google.android.material.R.attr.colorOnSecondaryContainer),
    MODERATE("中度", com.heban.pestai.base.R.attr.colorWarningContainer,
            com.heban.pestai.base.R.attr.colorOnWarningContainer),
    SEVERE("较重", com.google.android.material.R.attr.colorErrorContainer,
            com.google.android.material.R.attr.colorOnErrorContainer),
    CROP("作物", com.google.android.material.R.attr.colorTertiaryContainer,
            com.google.android.material.R.attr.colorOnTertiaryContainer),
    FAILED("失败", com.google.android.material.R.attr.colorSurfaceContainerHighest,
            com.google.android.material.R.attr.colorOnSurfaceVariant);

    @NonNull
    public final String text;
    @AttrRes
    public final int bgAttr;
    @AttrRes
    public final int fgAttr;

    RecordTag(@NonNull String text, @AttrRes int bgAttr, @AttrRes int fgAttr) {
        this.text = text;
        this.bgAttr = bgAttr;
        this.fgAttr = fgAttr;
    }

    @Nullable
    public static RecordTag of(@Nullable RecordItemVO item) {
        return item == null ? null : of(item.getMode(), item.getStatus(), item.getSeverity());
    }

    @Nullable
    public static RecordTag of(@Nullable String mode, @Nullable String status, @Nullable String severity) {
        if (RecognitionStatus.IMAGE_BAD.equals(status) || RecognitionStatus.FAILED.equals(status)) {
            return FAILED;
        }
        if (Mode.CROP.equals(mode)) {
            return CROP;
        }
        return ofSeverity(severity);
    }

    /** 只按严重程度取标签（R01 结论卡右上角），不在四档内返回 null */
    @Nullable
    public static RecordTag ofSeverity(@Nullable String severity) {
        if (severity == null) {
            return null;
        }
        switch (severity) {
            case Severity.HEALTHY:
                return HEALTHY;
            case Severity.MILD:
                return MILD;
            case Severity.MODERATE:
                return MODERATE;
            case Severity.SEVERE:
                return SEVERE;
            default:
                return null;
        }
    }
}
