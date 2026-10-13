package com.heban.pestai.base.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.heban.pestai.base.util.DimenUtils;

/**
 * 图片占位图 / 错误图：铺满的纯色底 + 居中的固定尺寸图标。
 * 没有固有尺寸，放进 centerCrop 的 ImageView 时图标不会被放大。
 */
public class PlaceholderDrawable extends Drawable {

    private final int backgroundColor;
    @Nullable
    private final Drawable icon;
    private final int iconSize;

    public PlaceholderDrawable(@NonNull Context context, @DrawableRes int iconRes,
                               @AttrRes int backgroundAttr, @AttrRes int iconTintAttr, int iconSizeDp) {
        this.backgroundColor = DimenUtils.themeColor(context, backgroundAttr);
        Drawable d = AppCompatResources.getDrawable(context, iconRes);
        if (d != null) {
            d = d.mutate();
            d.setTint(DimenUtils.themeColor(context, iconTintAttr));
        }
        this.icon = d;
        this.iconSize = DimenUtils.dp(context, iconSizeDp);
    }

    /** surfaceContainerHighest 底 + outline 色 24dp 图标 */
    @NonNull
    public static PlaceholderDrawable standard(@NonNull Context context, @DrawableRes int iconRes) {
        return new PlaceholderDrawable(context, iconRes,
                com.google.android.material.R.attr.colorSurfaceContainerHighest,
                com.google.android.material.R.attr.colorOutline, 24);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawColor(backgroundColor);
        if (icon != null) {
            Rect b = getBounds();
            int size = Math.min(iconSize, Math.min(b.width(), b.height()));
            int left = b.left + (b.width() - size) / 2;
            int top = b.top + (b.height() - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (icon != null) {
            icon.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (icon != null) {
            icon.setColorFilter(colorFilter);
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
