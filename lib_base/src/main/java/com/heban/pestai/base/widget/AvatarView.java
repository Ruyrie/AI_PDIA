package com.heban.pestai.base.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.transition.Transition;
import com.heban.pestai.base.R;
import com.heban.pestai.base.util.DimenUtils;

/**
 * 圆形头像：有头像地址时用 Glide circleCrop 加载；没有头像（或加载失败）时显示昵称首字，primary 底白字。
 * DataBinding：{@code app:avatarName="@{vm.user.nickname}" app:avatarImage="@{vm.user.avatarUrl}"}，
 * avatarImage 可传网络地址或本地 Uri 字符串（A06 选图后本地预览）。
 * <ul>
 *     <li>图片槽在加载期间保持 INVISIBLE（不能是 GONE）：GONE 的 View 不参与布局、尺寸为 0，
 *     Glide 的 SizeDeterminer 会一直等待尺寸，加载永远不会开始（曾导致所有页面的头像都不显示）。</li>
 *     <li>两个图片槽轮流使用（见 {@link AvatarLoadState}）：新头像加载到后台槽，成功后再交换并释放旧图，
 *     从本地预览切换到上传后的网络地址时不会闪出首字；加载失败显示首字，之后再设置同一地址会重新加载。</li>
 *     <li>显示头像时隐藏首字，带透明区域的头像不会透出文字。</li>
 * </ul>
 */
public class AvatarView extends FrameLayout {

    private final TextView textView;
    private final ImageView[] slots = new ImageView[2];
    private final SlotTarget[] targets = new SlotTarget[2];
    private final AvatarLoadState state = new AvatarLoadState();
    private float textSizeOverride = -1;

    public AvatarView(@NonNull Context context) {
        this(context, null);
    }

    public AvatarView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AvatarView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AvatarView);
            textSizeOverride = a.getDimension(R.styleable.AvatarView_avatarTextSize, -1);
            a.recycle();
        }
        setBackgroundResource(R.drawable.pest_bg_circle);
        if (getBackgroundTintList() == null) {
            setBackgroundTintList(ColorStateList.valueOf(
                    DimenUtils.themeColor(context, androidx.appcompat.R.attr.colorPrimary)));
        }
        setClipToOutline(true);

        textView = new TextView(context);
        textView.setGravity(Gravity.CENTER);
        textView.setTextColor(DimenUtils.themeColor(context, com.google.android.material.R.attr.colorOnPrimary));
        textView.getPaint().setFakeBoldText(false);
        textView.setTypeface(textView.getTypeface(), android.graphics.Typeface.NORMAL);
        textView.setIncludeFontPadding(false);
        addView(textView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        for (int i = 0; i < slots.length; i++) {
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            // INVISIBLE 而不是 GONE：仍参与布局，Glide 才能取得尺寸并开始加载
            image.setVisibility(INVISIBLE);
            addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            slots[i] = image;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (textSizeOverride > 0) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeOverride);
        } else {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(w, h) * 0.42f);
        }
    }

    /** 设置昵称，显示首字 */
    public void setName(@Nullable String nickname) {
        textView.setText(TextUtils.isEmpty(nickname) ? "" : nickname.substring(0, nickname.offsetByCodePoints(0, 1)));
    }

    /** 设置头像图片：网络地址、本地 Uri 字符串或 Uri；为空时显示首字 */
    public void setImage(@Nullable Object image) {
        int oldFront = state.frontSlot();
        int oldPending = state.pendingSlot();
        switch (state.request(image)) {
            case SHOW_INITIAL:
                releaseSlot(oldPending);
                releaseSlot(oldFront);
                updateInitial();
                break;
            case KEEP_FRONT:
                releaseSlot(oldPending);
                break;
            case LOAD:
                load(state.pendingSlot(), state.requested());
                break;
            case NONE:
            default:
                break;
        }
    }

    public TextView getTextView() {
        return textView;
    }

    /** 在后台槽加载；该槽上进行中的旧加载由 Glide 在 into() 时自动取消 */
    private void load(int slot, @Nullable Object model) {
        if (model == null) {
            return;
        }
        ImageView image = slots[slot];
        image.setVisibility(INVISIBLE);
        SlotTarget target = new SlotTarget(image, slot);
        targets[slot] = target;
        Glide.with(this)
                .load(model)
                .circleCrop()
                .into(target);
    }

    /** 取消该槽的加载或释放它显示的图片（不能在该槽自己的 Glide 回调中调用） */
    private void releaseSlot(int slot) {
        if (slot == AvatarLoadState.NO_SLOT) {
            return;
        }
        SlotTarget target = targets[slot];
        targets[slot] = null;
        if (target != null) {
            Glide.with(this).clear(target);
        }
        slots[slot].setImageDrawable(null);
        slots[slot].setVisibility(INVISIBLE);
    }

    private void onSlotReady(@NonNull SlotTarget target, @NonNull Drawable resource) {
        int slot = target.slot;
        if (targets[slot] != target) {
            return;
        }
        int oldFront = state.frontSlot();
        if (state.onLoaded(slot)) {
            show(slot, target, resource);
            if (oldFront != slot) {
                // 新图已显示，再释放旧图（另一个请求，可以在回调中清除）
                releaseSlot(oldFront);
            }
        } else if (state.frontSlot() == slot) {
            // 页面重新可见等情况下 Glide 重新交付了前台图片
            show(slot, target, resource);
        }
        updateInitial();
    }

    private void onSlotFailed(@NonNull SlotTarget target) {
        int slot = target.slot;
        if (targets[slot] != target) {
            return;
        }
        int oldFront = state.frontSlot();
        if (state.onFailed(slot)) {
            // 失败的请求不持有图片，留给下次 into() / 页面销毁时清除（不能在自己的回调中 clear）
            slots[slot].setImageDrawable(null);
            slots[slot].setVisibility(INVISIBLE);
            if (oldFront != slot) {
                releaseSlot(oldFront);
            }
            updateInitial();
        }
    }

    private void show(int slot, @NonNull SlotTarget target, @NonNull Drawable resource) {
        target.shown = resource;
        slots[slot].setImageDrawable(resource);
        slots[slot].setVisibility(VISIBLE);
    }

    /** 前台槽有图片时隐藏首字，否则显示首字 */
    private void updateInitial() {
        int front = state.frontSlot();
        boolean hasImage = front != AvatarLoadState.NO_SLOT && slots[front].getDrawable() != null;
        textView.setVisibility(hasImage ? INVISIBLE : VISIBLE);
    }

    /** 绑定到某个图片槽的 Glide 目标：结果交给 AvatarView 按状态处理，过期结果忽略 */
    private final class SlotTarget extends CustomViewTarget<ImageView, Drawable> {

        final int slot;
        /** 本目标设置到 ImageView 上的图片；Glide 回收时必须先从 ImageView 上移除 */
        @Nullable
        Drawable shown;

        SlotTarget(@NonNull ImageView view, int slot) {
            super(view);
            this.slot = slot;
        }

        @Override
        public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
            onSlotReady(this, resource);
        }

        @Override
        public void onLoadFailed(@Nullable Drawable errorDrawable) {
            onSlotFailed(this);
        }

        @Override
        protected void onResourceCleared(@Nullable Drawable placeholder) {
            // 图片即将被 Glide 回收：仍显示着就移除，避免绘制已回收的 Bitmap
            if (shown != null && getView().getDrawable() == shown) {
                getView().setImageDrawable(null);
                updateInitial();
            }
            shown = null;
        }
    }
}
