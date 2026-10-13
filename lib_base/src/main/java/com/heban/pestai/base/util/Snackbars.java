package com.heban.pestai.base.util;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.snackbar.Snackbar;
import com.heban.pestai.base.SnackbarAnchorProvider;

/**
 * 统一的 Snackbar：inverseSurface 底，4 秒自动消失，可带左侧图标与操作按钮（如「撤销」「重试」）。
 * Activity 实现 {@link SnackbarAnchorProvider} 时显示在底部导航 / 操作栏上方。
 */
public final class Snackbars {

    public static final int DURATION_MS = 4000;

    private Snackbars() {
    }

    @Nullable
    public static Snackbar show(@NonNull Activity activity, @Nullable CharSequence message) {
        return show(activity, message, 0, null, null);
    }

    @Nullable
    public static Snackbar show(@NonNull Activity activity, @Nullable CharSequence message, @DrawableRes int iconRes,
                                @Nullable CharSequence actionText, @Nullable View.OnClickListener action) {
        return show(activity, message, iconRes, actionText, action, DURATION_MS);
    }

    /** 自定义显示时长（毫秒），如撤销删除按剩余时间显示 */
    @Nullable
    public static Snackbar show(@NonNull Activity activity, @Nullable CharSequence message, @DrawableRes int iconRes,
                                @Nullable CharSequence actionText, @Nullable View.OnClickListener action,
                                int durationMs) {
        if (TextUtils.isEmpty(message)) {
            return null;
        }
        View root = activity.findViewById(android.R.id.content);
        Snackbar snackbar = Snackbar.make(root, message, durationMs);
        if (activity instanceof SnackbarAnchorProvider) {
            View anchor = ((SnackbarAnchorProvider) activity).getSnackbarAnchor();
            if (anchor != null && anchor.getVisibility() == View.VISIBLE) {
                snackbar.setAnchorView(anchor);
            }
        }
        if (iconRes != 0) {
            TextView text = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
            Drawable icon = AppCompatResources.getDrawable(activity, iconRes);
            if (text != null && icon != null) {
                // 左侧图标 20dp，inversePrimary 色
                icon = icon.mutate();
                int size = DimenUtils.dp(activity, 20);
                icon.setBounds(0, 0, size, size);
                icon.setTint(DimenUtils.themeColor(activity, com.google.android.material.R.attr.colorPrimaryInverse));
                text.setCompoundDrawablesRelative(icon, null, null, null);
                text.setCompoundDrawablePadding(DimenUtils.dp(activity, 12));
            }
        }
        if (actionText != null && action != null) {
            snackbar.setAction(actionText, action);
        }
        snackbar.show();
        return snackbar;
    }
}
