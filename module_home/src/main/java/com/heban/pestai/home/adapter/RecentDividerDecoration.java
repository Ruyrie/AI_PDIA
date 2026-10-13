package com.heban.pestai.home.adapter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.heban.pestai.base.util.DimenUtils;

/**
 * 最近识别行间分割线：除第一行外，每行顶部留出 1dp 并绘制 outlineVariant 色分割线（通栏）。
 */
public class RecentDividerDecoration extends RecyclerView.ItemDecoration {

    private final Paint paint = new Paint();
    private final int height;
    private final Rect bounds = new Rect();

    public RecentDividerDecoration(@NonNull Context context) {
        paint.setColor(DimenUtils.themeColor(context, com.google.android.material.R.attr.colorOutlineVariant));
        paint.setStyle(Paint.Style.FILL);
        height = Math.max(1, DimenUtils.dp(context, 1));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        outRect.set(0, position > 0 ? height : 0, 0, 0);
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int left = parent.getPaddingLeft();
        int right = parent.getWidth() - parent.getPaddingRight();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (parent.getChildAdapterPosition(child) <= 0) {
                continue;
            }
            parent.getDecoratedBoundsWithMargins(child, bounds);
            int top = bounds.top + Math.round(child.getTranslationY());
            paint.setAlpha(Math.round(255 * child.getAlpha()));
            canvas.drawRect(left, top, right, top + height, paint);
        }
    }
}
