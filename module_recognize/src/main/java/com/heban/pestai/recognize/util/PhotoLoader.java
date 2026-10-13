package com.heban.pestai.recognize.util;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.heban.pestai.recognize.R;

/**
 * 识别照片区的 Glide 加载：照片 ImageView 叠在占位层（surfaceContainerHighest 底 + 图标 + 说明）之上，
 * 加载中照片透明、露出占位层的 image 图标；失败时占位层改为 broken_image +「照片加载失败」，不影响其他区域。
 * Glide.with(View) 绑定所在 Activity 的生命周期：onStop 暂停、onDestroy 自动取消。
 */
public final class PhotoLoader {

    private PhotoLoader() {
    }

    /**
     * @param source    网络地址或本机 Uri 字符串（content:// / file://）
     * @param stateText 占位说明，可为 null（S02 只显示图标）
     * @param diskCache 本地临时照片传 false，不写磁盘缓存
     */
    public static void load(@NonNull ImageView photo, @NonNull ImageView stateIcon, @Nullable TextView stateText,
                            @Nullable String source, boolean diskCache) {
        if (source == null || source.trim().isEmpty()) {
            Glide.with(photo).clear(photo);
            photo.setImageDrawable(null);
            showState(stateIcon, stateText, true);
            return;
        }
        showState(stateIcon, stateText, false);
        RequestBuilder<Drawable> request = Glide.with(photo).load(source).centerCrop();
        if (!diskCache) {
            request = request.diskCacheStrategy(DiskCacheStrategy.NONE);
        }
        request.listener(new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model,
                                        @NonNull Target<Drawable> target, boolean isFirstResource) {
                showState(stateIcon, stateText, true);
                return false;
            }

            @Override
            public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target,
                                           @NonNull DataSource dataSource, boolean isFirstResource) {
                return false;
            }
        }).into(photo);
    }

    private static void showState(@NonNull ImageView icon, @Nullable TextView text, boolean failed) {
        icon.setImageResource(failed ? com.heban.pestai.base.R.drawable.ic_broken_image
                : com.heban.pestai.base.R.drawable.ic_image);
        if (text != null) {
            text.setText(failed ? R.string.scan_photo_failed : R.string.scan_photo_loading);
            text.setVisibility(View.VISIBLE);
        }
    }
}
