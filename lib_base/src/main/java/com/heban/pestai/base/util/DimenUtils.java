package com.heban.pestai.base.util;

import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

public final class DimenUtils {

    private DimenUtils() {
    }

    public static int dp(@NonNull Context context, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics()));
    }

    /** 读取主题颜色属性，如 androidx 的 colorPrimary 或 lib_base 的 colorWarningContainer */
    @ColorInt
    public static int themeColor(@NonNull Context context, @AttrRes int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        if (value.resourceId != 0) {
            return context.getColor(value.resourceId);
        }
        return value.data;
    }
}
