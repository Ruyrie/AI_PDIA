package com.heban.pestai.record.widget;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.record.R;

import java.lang.ref.WeakReference;

/**
 * 左滑删除容器（模块07 技术准备文档 2.7.5、2.8 第 1 点，课程「touch 事件传播」）。
 * <p>第 0 个子 View 是下层的删除按钮（宽 88dp，靠右），第 1 个子 View 是上层的记录内容；
 * 手势只改变内容层的 translationX，删除按钮位置不动，不需要重新布局。</p>
 * <ul>
 *     <li>{@link #dispatchTouchEvent}：每个事件先交给 VelocityTracker 记录，再正常分发。</li>
 *     <li>{@link #onInterceptTouchEvent}：ACTION_DOWN 时行已展开且按在内容区 → 拦截（松手只收起）；
 *     ACTION_MOVE 时横向位移大于 touchSlop 且大于纵向位移 → 拦截并开始拖动，
 *     调用父容器的 requestDisallowInterceptTouchEvent(true)，阻止 RecyclerView / SwipeRefreshLayout 抢走事件。</li>
 *     <li>{@link #onTouchEvent}：跟手平移（−88dp 到 0），松手按速度（600dp/s）和位置（44dp）吸附展开或回弹。</li>
 * </ul>
 * 同一时间只展开一行（静态弱引用 sOpened）；用 ViewOutlineProvider 按分组首末行裁切圆角（包括删除按钮）。
 */
public class SwipeItemLayout extends FrameLayout {

    private static final long ANIM_DURATION_MS = 200L;
    private static final float MIN_FLING_DP_PER_SECOND = 600f;

    /** 当前展开的行：弱引用，行被移出列表后不会造成内存泄漏 */
    @Nullable
    private static WeakReference<SwipeItemLayout> sOpened;

    private final int touchSlop;
    private final float minFlingVelocity;
    private final int menuWidth;
    private final int cornerRadius;

    private View contentView;
    @Nullable
    private VelocityTracker velocityTracker;
    @Nullable
    private ValueAnimator animator;
    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging;
    private boolean opened;
    private boolean roundTop = true;
    private boolean roundBottom = true;

    public SwipeItemLayout(@NonNull Context context) {
        this(context, null);
    }

    public SwipeItemLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeItemLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        minFlingVelocity = MIN_FLING_DP_PER_SECOND * getResources().getDisplayMetrics().density;
        menuWidth = getResources().getDimensionPixelSize(R.dimen.record_delete_width);
        cornerRadius = getResources().getDimensionPixelSize(R.dimen.record_group_radius);
        setOutlineProvider(new GroupOutlineProvider());
        setClipToOutline(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() != 2) {
            throw new IllegalStateException("SwipeItemLayout 需要两个子 View：第 0 个为删除按钮，第 1 个为内容层");
        }
        contentView = getChildAt(1);
    }

    // ---------------------------------------------------------------- 事件分发

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        } else if (action == MotionEvent.ACTION_DOWN) {
            velocityTracker.clear();
        }
        // 在分发入口统一记录，拦截前后的事件都不会漏记
        velocityTracker.addMovement(ev);
        boolean handled = super.dispatchTouchEvent(ev);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // 普通轻点由内容层消费，不会经过本控件的 onTouchEvent()，这里兜底回收 VelocityTracker
            endDrag();
        }
        return handled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        float x = ev.getX();
        float y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                lastX = x;
                dragging = false;
                cancelAnimator();
                closeOthers();
                // 行已展开（或正在回弹）且按在内容区：拦截，内容层收不到这次点击，松手只收起
                float translation = contentView.getTranslationX();
                return translation < 0 && x < getWidth() + translation;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    return true;
                }
                if (isHorizontalDrag(x, y)) {
                    // 返回 true 后内容层收到 ACTION_CANCEL，按下态取消，不会触发点击
                    startDrag(x);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX();
        float y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 消费 DOWN，才能继续收到同一序列的 MOVE / UP
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    if (!isHorizontalDrag(x, y)) {
                        return true;
                    }
                    startDrag(x);
                }
                float target = clamp(contentView.getTranslationX() + (x - lastX));
                // translationX 是渲染属性，只更新渲染节点，不触发 measure / layout
                contentView.setTranslationX(target);
                lastX = x;
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    settle();
                } else if (contentView.getTranslationX() < 0) {
                    // 行已展开时轻点内容区：只收起，不进入详情
                    close();
                }
                endDrag();
                return true;
            case MotionEvent.ACTION_CANCEL:
                close();
                endDrag();
                return true;
            default:
                return true;
        }
    }

    /** 横向位移大于 touchSlop 且大于纵向位移才算横向滑动，避免和列表纵向滚动冲突 */
    private boolean isHorizontalDrag(float x, float y) {
        float dx = Math.abs(x - downX);
        float dy = Math.abs(y - downY);
        return dx > touchSlop && dx > dy;
    }

    private void startDrag(float x) {
        dragging = true;
        // 从当前点开始跟手，避免内容突然跳动 touchSlop 的距离
        lastX = x;
        cancelAnimator();
        ViewParent parent = getParent();
        if (parent != null) {
            // 沿父链传给 RecyclerView 和 SwipeRefreshLayout：本事件序列内不再询问它们是否拦截
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private void endDrag() {
        dragging = false;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    /** 松手：向左快于 600dp/s 展开，向右快于 600dp/s 收起；否则左移超过一半（44dp）展开，不足回弹 */
    private void settle() {
        float vx = 0f;
        if (velocityTracker != null) {
            velocityTracker.computeCurrentVelocity(1000);
            vx = velocityTracker.getXVelocity();
        }
        float translation = contentView.getTranslationX();
        if (vx < -minFlingVelocity) {
            open();
        } else if (vx > minFlingVelocity) {
            close();
        } else if (-translation > menuWidth / 2f) {
            open();
        } else {
            close();
        }
    }

    private float clamp(float translation) {
        return Math.max(-menuWidth, Math.min(0f, translation));
    }

    // ---------------------------------------------------------------- 展开 / 收起

    public void open() {
        opened = true;
        sOpened = new WeakReference<>(this);
        smoothTo(-menuWidth);
    }

    public void close() {
        opened = false;
        if (sOpened != null && sOpened.get() == this) {
            sOpened = null;
        }
        smoothTo(0f);
    }

    public boolean isOpened() {
        return opened;
    }

    /** 不做动画，直接复位为收起状态（onBindViewHolder() 中调用，防止复用的行带着展开状态） */
    public void resetImmediately() {
        cancelAnimator();
        endDrag();
        opened = false;
        if (sOpened != null && sOpened.get() == this) {
            sOpened = null;
        }
        if (contentView != null) {
            contentView.setTranslationX(0f);
        }
    }

    /** 收起当前展开的行（列表开始滚动、切换筛选、下拉刷新时调用） */
    public static void closeOpened() {
        SwipeItemLayout opened = sOpened == null ? null : sOpened.get();
        if (opened != null) {
            opened.close();
        }
    }

    private void closeOthers() {
        SwipeItemLayout opened = sOpened == null ? null : sOpened.get();
        if (opened != null && opened != this) {
            opened.close();
        }
    }

    /** 200ms 减速动画把内容层平移到目标位置，在主线程逐帧更新 */
    private void smoothTo(float target) {
        cancelAnimator();
        float from = contentView.getTranslationX();
        if (from == target) {
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(from, target);
        anim.setDuration(ANIM_DURATION_MS);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> contentView.setTranslationX((float) a.getAnimatedValue()));
        animator = anim;
        anim.start();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // 行被移出列表时停止动画，直接停在目标位置，避免继续操作已回收的 View
        cancelAnimator();
        endDrag();
        if (contentView != null) {
            contentView.setTranslationX(opened ? -menuWidth : 0f);
        }
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- 分组圆角

    /**
     * 设置分组圆角：组内第一行只保留上圆角、最后一行只保留下圆角，只有一行时四角圆角，中间行四角直角。
     * 用轮廓裁切整行（包括左滑露出的删除按钮），不需要圆角的一侧把轮廓向外延伸一个半径，让圆角落在可见区域之外。
     */
    public void setCorners(boolean top, boolean bottom) {
        if (roundTop == top && roundBottom == bottom) {
            return;
        }
        roundTop = top;
        roundBottom = bottom;
        invalidateOutline();
    }

    private final class GroupOutlineProvider extends ViewOutlineProvider {
        @Override
        public void getOutline(View view, Outline outline) {
            int width = view.getWidth();
            int height = view.getHeight();
            int top = roundTop ? 0 : -cornerRadius;
            int bottom = roundBottom ? height : height + cornerRadius;
            outline.setRoundRect(0, top, width, bottom, cornerRadius);
        }
    }
}
