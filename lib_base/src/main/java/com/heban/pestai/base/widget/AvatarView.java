package com.heban.pestai.base.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
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
 */
public class AvatarView extends FrameLayout {

    private final TextView textView;
    private final ImageView imageView;
    private float textSizeOverride = -1;
    @Nullable
    private Object currentImage;

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

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setVisibility(GONE);
        addView(imageView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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
        if (image instanceof String && TextUtils.isEmpty((String) image)) {
            image = null;
        }
        if (image != null && image.equals(currentImage) && imageView.getVisibility() == VISIBLE) {
            return;
        }
        currentImage = image;
        if (image == null) {
            Glide.with(this).clear(imageView);
            imageView.setVisibility(GONE);
            return;
        }
        Glide.with(this)
                .load(image)
                .circleCrop()
                .into(new CustomViewTarget<ImageView, android.graphics.drawable.Drawable>(imageView) {
                    @Override
                    public void onResourceReady(@NonNull android.graphics.drawable.Drawable resource,
                                                @Nullable Transition<? super android.graphics.drawable.Drawable> transition) {
                        imageView.setImageDrawable(resource);
                        imageView.setVisibility(VISIBLE);
                    }

                    @Override
                    public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                        imageView.setVisibility(GONE);
                    }

                    @Override
                    protected void onResourceCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        imageView.setImageDrawable(null);
                    }
                });
    }

    public TextView getTextView() {
        return textView;
    }
}
