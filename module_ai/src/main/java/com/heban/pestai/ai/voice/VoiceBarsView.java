package com.heban.pestai.ai.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.base.util.DimenUtils;

import java.util.Arrays;

/**
 * 录音面板中的音量条：一排 3dp 宽的圆头竖条，从右向左滚动，最右边是最新的音量（{@link #push(float)}，约每 50ms 一次）。
 * 每根条按位置乘以中间高、两边低的系数，静音时为小圆点。颜色随面板（正常 / 取消）由 {@link #setBarColor(int)} 设置。
 */
public class VoiceBarsView extends View {

    /** 竖条数量 */
    private static final int BAR_COUNT = 19;
    private static final float BAR_WIDTH_DP = 3;
    private static final float BAR_GAP_DP = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 各竖条的音量（0 ~ 1），下标 0 为最旧 */
    private final float[] levels = new float[BAR_COUNT];
    private final float barWidth;
    private final float barGap;

    public VoiceBarsView(@NonNull Context context) {
        this(context, null);
    }

    public VoiceBarsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        barWidth = DimenUtils.dp(context, BAR_WIDTH_DP);
        barGap = DimenUtils.dp(context, BAR_GAP_DP);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(barWidth);
        // 装饰性图形，无障碍服务不需要读出
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setBarColor(@ColorInt int color) {
        if (paint.getColor() != color) {
            paint.setColor(color);
            invalidate();
        }
    }

    /** 追加最新音量（0 ~ 1），整体左移一格 */
    public void push(float level) {
        System.arraycopy(levels, 1, levels, 0, BAR_COUNT - 1);
        levels[BAR_COUNT - 1] = Math.max(0f, Math.min(1f, level));
        invalidate();
    }

    /** 新的一次录音开始前清空 */
    public void reset() {
        Arrays.fill(levels, 0f);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = (int) Math.ceil(BAR_COUNT * barWidth + (BAR_COUNT - 1) * barGap)
                + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float contentWidth = BAR_COUNT * barWidth + (BAR_COUNT - 1) * barGap;
        float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - contentWidth) / 2f;
        float centerY = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        // 圆头会向两端各多出半个条宽
        float maxHalf = Math.max(0f, (getHeight() - getPaddingTop() - getPaddingBottom() - barWidth) / 2f);
        float center = (BAR_COUNT - 1) / 2f;
        for (int i = 0; i < BAR_COUNT; i++) {
            // 中间的条最高，两端约为 55%
            float shape = 1f - 0.45f * Math.abs(i - center) / center;
            float half = maxHalf * levels[i] * shape;
            float x = left + i * (barWidth + barGap) + barWidth / 2f;
            canvas.drawLine(x, centerY - half, x, centerY + half, paint);
        }
    }
}
