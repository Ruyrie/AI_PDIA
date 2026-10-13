package com.heban.pestai.ai.util;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * 卡片按压反馈：按下缩放到 0.96，松开 / 取消回弹到 1（100ms）。
 * 只观察触摸事件、不消费，水波纹与点击仍由 View 自己处理。
 */
public final class AiPressFeedback {

    public static final float PRESSED_SCALE = 0.96f;
    public static final long DURATION_MS = 100;

    private AiPressFeedback() {
    }

    @SuppressLint("ClickableViewAccessibility") // 不消费事件，点击与无障碍点击仍走 performClick
    public static void attach(@NonNull View view) {
        DecelerateInterpolator interpolator = new DecelerateInterpolator();
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.isEnabled()) {
                        animate(v, PRESSED_SCALE, interpolator);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate(v, 1f, interpolator);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private static void animate(@NonNull View v, float scale, @NonNull DecelerateInterpolator interpolator) {
        v.animate().cancel();
        v.animate().scaleX(scale).scaleY(scale).setDuration(DURATION_MS).setInterpolator(interpolator).start();
    }
}
