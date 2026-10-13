package com.heban.pestai.base.util;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge-to-Edge 下的系统栏内边距处理（targetSdk 36 强制全面屏绘制）。
 * 在原有 padding 基础上叠加系统栏 / 软键盘高度，可重复回调不会累加。
 */
public final class InsetsHelper {

    private InsetsHelper() {
    }

    /** 根布局四周避开状态栏、导航栏与刘海 */
    public static void applySystemBars(@NonNull View view) {
        apply(view, true, true, false);
    }

    /**
     * @param top    顶部是否避开状态栏
     * @param bottom 底部是否避开导航栏
     * @param ime    底部是否同时避开软键盘（对话页、表单页底部按钮），取两者较大值
     */
    public static void apply(@NonNull View view, boolean top, boolean bottom, boolean ime) {
        final int left = view.getPaddingLeft();
        final int topPad = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottomPad = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            int bottomInset = bottom ? bars.bottom : 0;
            if (ime) {
                bottomInset = Math.max(bottomInset, windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            }
            v.setPadding(left + bars.left, topPad + (top ? bars.top : 0), right + bars.right, bottomPad + bottomInset);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
