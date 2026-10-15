package com.heban.pestai.ai.chat;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.heban.pestai.ai.R;
import com.heban.pestai.base.widget.PlaceholderDrawable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Q02 一条用户消息的图片（1–9 张）：按 {@link ImageGridSpec} 排成单图 / 2 列 / 3 列九宫格，centerCrop + 圆角。
 * 每格是一个 ImageView：背景为 ai_bg_image_placeholder（按格子改圆角，同时作为 clipToOutline 的轮廓），
 * 加载失败显示 broken_image 占位；同一格图片源不变时不重新加载（发送后、流式刷新时不闪白）。
 * 可用宽度不够时整体等比缩小。点按某一格回调其序号（打开全屏预览）。
 */
public class ImageGridView extends ViewGroup {

    /** 点按第 index 张（从 0 开始） */
    public interface OnImageClickListener {
        void onImageClick(int index);
    }

    private final List<ImageView> cells = new ArrayList<>();
    /** 每格当前加载的图片源 */
    private final String[] boundSources = new String[ImageGridSpec.MAX_IMAGES];
    private final float density;
    @NonNull
    private ImageGridSpec spec = ImageGridSpec.of(0);
    /** 实际排列时 1dp 对应的像素（可用宽度不够时小于 density） */
    private float unit;
    @Nullable
    private OnImageClickListener listener;

    public ImageGridView(@NonNull Context context) {
        this(context, null);
    }

    public ImageGridView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImageGridView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        unit = density;
    }

    public void setOnImageClickListener(@Nullable OnImageClickListener listener) {
        this.listener = listener;
    }

    /**
     * 显示图片。
     *
     * @param sources   每格的图片源（本地 Uri 或服务器地址），超过 9 张只显示前 9 张
     * @param fallbacks 与 sources 一一对应的备用地址（本地图片读取失败时改用服务器地址），可为空列表
     */
    public void setImages(@NonNull RequestManager glide, @NonNull List<String> sources,
                          @NonNull List<String> fallbacks) {
        ImageGridSpec newSpec = ImageGridSpec.of(sources.size());
        boolean layoutChanged = !newSpec.equals(spec);
        spec = newSpec;
        while (cells.size() < spec.count) {
            cells.add(createCell(cells.size()));
        }
        int corner = Math.round(spec.cornerDp * density);
        for (int i = 0; i < cells.size(); i++) {
            ImageView cell = cells.get(i);
            if (i >= spec.count) {
                if (cell.getVisibility() != GONE) {
                    cell.setVisibility(GONE);
                }
                if (boundSources[i] != null) {
                    boundSources[i] = null;
                    glide.clear(cell);
                }
                continue;
            }
            cell.setVisibility(VISIBLE);
            Drawable background = cell.getBackground();
            if (background instanceof GradientDrawable
                    && ((GradientDrawable) background).getCornerRadius() != corner) {
                ((GradientDrawable) background).setCornerRadius(corner);
                cell.invalidateOutline();
            }
            cell.setContentDescription(spec.count == 1
                    ? getContext().getString(R.string.ai_photo)
                    : getContext().getString(R.string.ai_photo_index, i + 1));
            String source = sources.get(i);
            if (!Objects.equals(source, boundSources[i])) {
                boundSources[i] = source;
                load(glide, cell, source, i < fallbacks.size() ? fallbacks.get(i) : null);
            }
        }
        if (layoutChanged) {
            requestLayout();
        }
    }

    private void load(@NonNull RequestManager glide, @NonNull ImageView cell, @Nullable String source,
                      @Nullable String fallback) {
        Drawable error = PlaceholderDrawable.standard(getContext(), com.heban.pestai.base.R.drawable.ic_broken_image);
        RequestBuilder<Drawable> request = glide.load(source).centerCrop();
        if (fallback != null && !fallback.isEmpty() && !fallback.equals(source)) {
            request = request.error(glide.load(fallback).centerCrop().error(error));
        } else {
            request = request.error(error);
        }
        request.into(cell);
    }

    @NonNull
    private ImageView createCell(int index) {
        ImageView cell = new ImageView(getContext());
        cell.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable placeholder = ContextCompat.getDrawable(getContext(), R.drawable.ai_bg_image_placeholder);
        cell.setBackground(placeholder == null ? null : placeholder.mutate());
        cell.setClipToOutline(true);
        cell.setFocusable(true);
        cell.setOnClickListener(v -> {
            if (listener != null) {
                listener.onImageClick(index);
            }
        });
        addView(cell, new LayoutParams(0, 0));
        return cell;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = spec.count;
        if (count == 0) {
            setMeasuredDimension(getPaddingLeft() + getPaddingRight(), getPaddingTop() + getPaddingBottom());
            return;
        }
        int padH = getPaddingLeft() + getPaddingRight();
        int wanted = Math.round(spec.widthDp() * density);
        int available = MeasureSpec.getSize(widthMeasureSpec) - padH;
        unit = density;
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && available > 0 && wanted > available) {
            // 可用宽度不够（分屏、小屏）：等比缩小
            unit = density * available / wanted;
        }
        int cellWidth = Math.round(spec.cellWidthDp * unit);
        int cellHeight = Math.round(spec.cellHeightDp * unit);
        int childWidthSpec = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY);
        for (int i = 0; i < count && i < cells.size(); i++) {
            cells.get(i).measure(childWidthSpec, childHeightSpec);
        }
        setMeasuredDimension(Math.round(spec.widthDp() * unit) + padH,
                Math.round(spec.heightDp() * unit) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int innerWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < spec.count && i < cells.size(); i++) {
            View cell = cells.get(i);
            int width = cell.getMeasuredWidth();
            int height = cell.getMeasuredHeight();
            int x = Math.round(spec.cellLeftDp(i) * unit);
            int left = rtl ? getPaddingLeft() + innerWidth - x - width : getPaddingLeft() + x;
            int top = getPaddingTop() + Math.round(spec.cellTopDp(i) * unit);
            cell.layout(left, top, left + width, top + height);
        }
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }
}
