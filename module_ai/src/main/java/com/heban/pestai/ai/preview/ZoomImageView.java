package com.heban.pestai.ai.preview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * 全屏预览中可缩放的图片（Matrix 实现，scaleType 固定为 MATRIX）：
 * <ul>
 *     <li>1 倍时 fitCenter 完整显示；双指缩放 1–4 倍；双击在 1 倍与 2.5 倍之间切换（以点按位置为中心，250ms 动画）；</li>
 *     <li>放大后单指拖动、松手惯性滑动，图片边缘不会拖进屏幕内（比屏幕小的方向居中）；</li>
 *     <li>与 ViewPager2 配合：放大且能左右拖动时 requestDisallowInterceptTouchEvent(true)，不让翻页抢走手势；
 *     拖到左右边缘后继续同方向拖动则交还给 ViewPager2 翻页；1 倍时单指左右滑动直接翻页；双指按下时始终不让拦截；</li>
 *     <li>更换图片、尺寸变化（旋转屏幕）时恢复 1 倍；翻页 / 回收时由外部调用 {@link #resetZoom()}。</li>
 * </ul>
 */
public class ZoomImageView extends AppCompatImageView {

    public static final float MIN_SCALE = 1f;
    public static final float MAX_SCALE = 4f;
    public static final float DOUBLE_TAP_SCALE = 2.5f;
    private static final long ZOOM_ANIM_MS = 250;
    /** 判断是否已放大、是否到边缘的容差（px） */
    private static final float EPSILON = 0.5f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final RectF rect = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;
    private final Runnable flingRunnable = this::stepFling;
    /** 1 倍（fitCenter）时 drawable 像素到 View 像素的缩放 */
    private float baseScale = 1f;
    @Nullable
    private ValueAnimator zoomAnimator;
    private int flingX;
    private int flingY;

    public ZoomImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        super.setScaleType(ScaleType.MATRIX);
        scroller = new OverScroller(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                cancelMotion();
                disallowParentIntercept(true);
                return getDrawable() != null;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float target = clamp(getScale() * detector.getScaleFactor(), MIN_SCALE, MAX_SCALE);
                zoomTo(target, detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        // 双指缩放时不要把「快速缩放」（双击后拖动）当作缩放手势，避免与双击切换冲突
        scaleDetector.setQuickScaleEnabled(false);
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                return performClick();
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (getDrawable() == null) {
                    return false;
                }
                animateZoom(isZoomed() ? MIN_SCALE : DOUBLE_TAP_SCALE, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress() || !isZoomed() || e2.getPointerCount() > 1) {
                    return false;
                }
                RectF r = displayRect();
                if (r != null && Math.abs(dx) > Math.abs(dy)) {
                    // 已到左 / 右边缘还继续往外拖：交给 ViewPager2 翻页
                    boolean atLeft = r.left >= getPaddingLeft() - EPSILON;
                    boolean atRight = r.right <= getWidth() - getPaddingRight() + EPSILON;
                    if ((dx < 0 && atLeft) || (dx > 0 && atRight)) {
                        disallowParentIntercept(false);
                    }
                }
                matrix.postTranslate(-dx, -dy);
                applyMatrix();
                return true;
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float vx, float vy) {
                if (scaleDetector.isInProgress() || !isZoomed()) {
                    return false;
                }
                startFling(vx, vy);
                return true;
            }
        });
    }

    // ==================== 图片与尺寸 ====================

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        // 父类构造方法中也会调用（此时字段还没有初始化）
        if (gestureDetector != null) {
            resetZoom();
        }
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        // 始终使用 MATRIX
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoom();
    }

    /** 恢复 1 倍（fitCenter 居中），停止动画与惯性滑动 */
    public void resetZoom() {
        cancelMotion();
        Drawable d = getDrawable();
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        int dw = d == null ? 0 : d.getIntrinsicWidth();
        int dh = d == null ? 0 : d.getIntrinsicHeight();
        matrix.reset();
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) {
            baseScale = 1f;
        } else {
            baseScale = Math.min(vw / (float) dw, vh / (float) dh);
            matrix.setScale(baseScale, baseScale);
            matrix.postTranslate(getPaddingLeft() + (vw - dw * baseScale) / 2f,
                    getPaddingTop() + (vh - dh * baseScale) / 2f);
        }
        setImageMatrix(matrix);
    }

    /** 相对 1 倍（fitCenter）的缩放倍数 */
    public float getScale() {
        matrix.getValues(values);
        return baseScale > 0 ? values[Matrix.MSCALE_X] / baseScale : 1f;
    }

    public boolean isZoomed() {
        return getScale() > MIN_SCALE + 0.01f;
    }

    // ==================== 触摸 ====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) {
            return super.onTouchEvent(event);
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelFling();
            // 放大且能左右拖动：不让 ViewPager2 拦截；1 倍时交给 ViewPager2 左右翻页
            disallowParentIntercept(isZoomed() && canPanHorizontally());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            disallowParentIntercept(true);
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private boolean canPanHorizontally() {
        RectF r = displayRect();
        return r != null && r.width() > getWidth() - getPaddingLeft() - getPaddingRight() + EPSILON;
    }

    // ==================== 缩放与移动 ====================

    /** 以 (focusX, focusY) 为中心缩放到 target 倍 */
    private void zoomTo(float target, float focusX, float focusY) {
        float delta = target / getScale();
        matrix.postScale(delta, delta, focusX, focusY);
        applyMatrix();
    }

    private void animateZoom(float target, float focusX, float focusY) {
        cancelMotion();
        ValueAnimator animator = ValueAnimator.ofFloat(getScale(), target);
        animator.setDuration(ZOOM_ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> zoomTo((float) a.getAnimatedValue(), focusX, focusY));
        zoomAnimator = animator;
        animator.start();
    }

    private void startFling(float vx, float vy) {
        RectF r = displayRect();
        if (r == null) {
            return;
        }
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        // 以「内容左上角相对可见区域左上角的偏移」为滚动位置
        int startX = Math.round(getPaddingLeft() - r.left);
        int startY = Math.round(getPaddingTop() - r.top);
        int maxX = Math.max(0, Math.round(r.width() - vw));
        int maxY = Math.max(0, Math.round(r.height() - vh));
        scroller.forceFinished(true);
        scroller.fling(startX, startY, Math.round(-vx), Math.round(-vy),
                0, maxX, 0, maxY);
        flingX = startX;
        flingY = startY;
        postOnAnimation(flingRunnable);
    }

    private void stepFling() {
        if (!scroller.computeScrollOffset()) {
            return;
        }
        int x = scroller.getCurrX();
        int y = scroller.getCurrY();
        matrix.postTranslate(flingX - x, flingY - y);
        flingX = x;
        flingY = y;
        applyMatrix();
        postOnAnimation(flingRunnable);
    }

    private void cancelFling() {
        scroller.forceFinished(true);
        removeCallbacks(flingRunnable);
    }

    private void cancelMotion() {
        cancelFling();
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
    }

    /** 限制边界后应用矩阵 */
    private void applyMatrix() {
        clampBounds();
        setImageMatrix(matrix);
    }

    /** 图片比可见区域小的方向居中；比可见区域大的方向不留空边 */
    private void clampBounds() {
        RectF r = displayRect();
        if (r == null) {
            return;
        }
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float vw = getWidth() - getPaddingLeft() - getPaddingRight();
        float vh = getHeight() - getPaddingTop() - getPaddingBottom();
        float dx = 0;
        float dy = 0;
        if (r.width() <= vw) {
            dx = left + (vw - r.width()) / 2f - r.left;
        } else if (r.left > left) {
            dx = left - r.left;
        } else if (r.right < left + vw) {
            dx = left + vw - r.right;
        }
        if (r.height() <= vh) {
            dy = top + (vh - r.height()) / 2f - r.top;
        } else if (r.top > top) {
            dy = top - r.top;
        } else if (r.bottom < top + vh) {
            dy = top + vh - r.bottom;
        }
        matrix.postTranslate(dx, dy);
    }

    /** 图片当前在 View 中的位置；没有图片返回 null */
    @Nullable
    private RectF displayRect() {
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) {
            return null;
        }
        rect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);
        return rect;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelMotion();
        super.onDetachedFromWindow();
    }
}
