package com.heban.pestai.base.binding;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.databinding.BindingAdapter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.heban.pestai.base.R;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.base.widget.AvatarView;
import com.heban.pestai.base.widget.PlaceholderDrawable;

/**
 * lib_base 通用 BindingAdapter。业务组件自定义的绑定属性请加组件前缀，不要与这里的属性重名：
 * visible、invisible、selected、onSafeClick、imageUrl/imageCornerRadius、indicatorSelected、avatarName、avatarImage。
 */
public final class BaseBindingAdapters {

    private BaseBindingAdapters() {
    }

    /** true → VISIBLE，false → GONE */
    @BindingAdapter("visible")
    public static void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** true → INVISIBLE（保留占位），false → VISIBLE */
    @BindingAdapter("invisible")
    public static void setInvisible(View view, boolean invisible) {
        view.setVisibility(invisible ? View.INVISIBLE : View.VISIBLE);
    }

    @BindingAdapter("selected")
    public static void setSelected(View view, boolean selected) {
        view.setSelected(selected);
    }

    /** 防连点点击：500ms 内只响应第一次 */
    @BindingAdapter("onSafeClick")
    public static void setOnSafeClick(View view, @Nullable View.OnClickListener listener) {
        if (listener == null) {
            view.setOnClickListener(null);
        } else {
            RxClick.throttle(view, listener);
        }
    }

    /**
     * Glide 加载网络图片或本地 Uri：centerCrop + 可选圆角，统一占位图 / 错误图。
     * 圆角单位 dp，如 {@code app:imageCornerRadius="@{12}"}。
     */
    @BindingAdapter(value = {"imageUrl", "imageCornerRadius"}, requireAll = false)
    public static void loadImage(ImageView view, @Nullable Object source, int cornerRadiusDp) {
        // 占位 / 错误图没有固有尺寸，centerCrop 时图标保持 24dp 居中
        PlaceholderDrawable placeholder = PlaceholderDrawable.standard(view.getContext(), R.drawable.ic_image);
        PlaceholderDrawable error = PlaceholderDrawable.standard(view.getContext(), R.drawable.ic_broken_image);
        if (source == null || (source instanceof String && TextUtils.isEmpty((String) source))) {
            Glide.with(view).clear(view);
            view.setImageDrawable(placeholder);
            return;
        }
        if (cornerRadiusDp > 0) {
            Glide.with(view).load(source)
                    .placeholder(placeholder)
                    .error(error)
                    .transform(new CenterCrop(), new RoundedCorners(DimenUtils.dp(view.getContext(), cornerRadiusDp)))
                    .into(view);
        } else {
            Glide.with(view).load(source)
                    .placeholder(placeholder)
                    .error(error)
                    .centerCrop()
                    .into(view);
        }
    }

    /** 引导页指示点：选中 24dp primary，未选 8dp outlineVariant，宽度动画过渡 */
    @BindingAdapter("indicatorSelected")
    public static void setIndicatorSelected(View view, boolean selected) {
        int target = DimenUtils.dp(view.getContext(), selected ? 24 : 8);
        int color = DimenUtils.themeColor(view.getContext(), selected
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOutlineVariant);
        if (view.getBackground() == null) {
            view.setBackgroundResource(R.drawable.pest_bg_pill);
        }
        view.setBackgroundTintList(ColorStateList.valueOf(color));
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (lp.width <= 0 || !view.isLaidOut()) {
            lp.width = target;
            view.setLayoutParams(lp);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(lp.width, target);
        animator.setDuration(200);
        animator.addUpdateListener(a -> {
            lp.width = (int) a.getAnimatedValue();
            view.setLayoutParams(lp);
        });
        animator.start();
    }

    @BindingAdapter("avatarName")
    public static void setAvatarName(AvatarView view, @Nullable String nickname) {
        view.setName(nickname);
    }

    @BindingAdapter("avatarImage")
    public static void setAvatarImage(AvatarView view, @Nullable Object image) {
        view.setImage(image);
    }
}
