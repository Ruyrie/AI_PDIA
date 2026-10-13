package com.heban.pestai.account.ui.common;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 登录方式 Tab 的指示条：固定宽度（56dp）、高 3dp、上方圆角 3dp，水平居中于 TabLayout 给出的选中区域；
 * TabLayout 切换时插值移动 bounds，指示条随之滑动。颜色通过 setTint（tabIndicatorColor）设置。
 */
public class TabIndicatorDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int width;
    private final int height;
    private final float radius;

    public TabIndicatorDrawable(int widthPx, int heightPx, int color) {
        this.width = widthPx;
        this.height = heightPx;
        this.radius = heightPx;
        paint.setColor(color);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        float cx = b.exactCenterX();
        rect.set(cx - width / 2f, b.bottom - height, cx + width / 2f, b.bottom);
        path.reset();
        path.addRoundRect(rect, new float[]{radius, radius, radius, radius, 0, 0, 0, 0}, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        if (tint != null) {
            paint.setColor(tint.getDefaultColor());
            invalidateSelf();
        }
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @SuppressWarnings("deprecation")
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
