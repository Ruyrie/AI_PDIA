package com.heban.pestai.recognize.model;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.Severity;

/**
 * R01 结论卡右上角的严重程度标签：文字、底色 / 文字色（主题属性）、图标（与文字同色）。
 *
 * <table>
 *     <tr><td>健康</td><td>primaryContainer / onPrimaryContainer</td><td>check_circle</td></tr>
 *     <tr><td>轻度</td><td>secondaryContainer / onSecondaryContainer</td><td>info</td></tr>
 *     <tr><td>中度</td><td>warningContainer / onWarningContainer</td><td>warning</td></tr>
 *     <tr><td>较重</td><td>errorContainer / onErrorContainer</td><td>error</td></tr>
 * </table>
 */
public enum SeverityLevel {

    HEALTHY(Severity.HEALTHY,
            com.google.android.material.R.attr.colorPrimaryContainer,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            com.heban.pestai.base.R.drawable.ic_check_circle),
    MILD(Severity.MILD,
            com.google.android.material.R.attr.colorSecondaryContainer,
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            com.heban.pestai.base.R.drawable.ic_info),
    MODERATE(Severity.MODERATE,
            com.heban.pestai.base.R.attr.colorWarningContainer,
            com.heban.pestai.base.R.attr.colorOnWarningContainer,
            com.heban.pestai.base.R.drawable.ic_warning),
    SEVERE(Severity.SEVERE,
            com.google.android.material.R.attr.colorErrorContainer,
            com.google.android.material.R.attr.colorOnErrorContainer,
            com.heban.pestai.base.R.drawable.ic_error);

    /** 界面文字，与服务器返回的中文取值相同 */
    @NonNull
    public final String text;
    @AttrRes
    public final int bgAttr;
    @AttrRes
    public final int fgAttr;
    @DrawableRes
    public final int iconRes;

    SeverityLevel(@NonNull String text, @AttrRes int bgAttr, @AttrRes int fgAttr, @DrawableRes int iconRes) {
        this.text = text;
        this.bgAttr = bgAttr;
        this.fgAttr = fgAttr;
        this.iconRes = iconRes;
    }

    /** 服务器取值（健康 / 轻度 / 中度 / 较重）→ 枚举；空值与未知取值返回 null（隐藏标签） */
    @Nullable
    public static SeverityLevel of(@Nullable String severity) {
        if (severity == null) {
            return null;
        }
        for (SeverityLevel level : values()) {
            if (level.text.equals(severity.trim())) {
                return level;
            }
        }
        return null;
    }
}
