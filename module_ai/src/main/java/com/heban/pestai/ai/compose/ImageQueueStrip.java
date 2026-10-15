package com.heban.pestai.ai.compose;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.bumptech.glide.RequestManager;
import com.heban.pestai.ai.preview.ImagePreviewActivity;

import java.util.List;

/**
 * 把输入栏上方的横向 RecyclerView 绑定为待发送图片缩略图条（Q01、Q02 共用）：
 * ✕ 移除并取消上传；点按失败的缩略图重新上传；点按已上传的缩略图全屏预览队列中的全部图片（从点按的那张开始）；
 * 新加入图片后滚到最后一张。缩略图条本身的显示 / 隐藏由布局绑定（没有图片时隐藏）。
 */
public final class ImageQueueStrip {

    private ImageQueueStrip() {
    }

    public static void setup(@NonNull RecyclerView list, @NonNull RequestManager glide,
                             @NonNull LifecycleOwner owner, @NonNull ImageComposer composer) {
        Context context = list.getContext();
        list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            // 状态变化（上传中 → 完成）不做淡入淡出
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        PendingImageAdapter adapter = new PendingImageAdapter(glide, new PendingImageAdapter.Listener() {
            @Override
            public void onImageClick(@NonNull PendingImage image, int position) {
                if (image.isFailed()) {
                    composer.retryImage(image.getId());
                } else if (image.isDone()) {
                    List<String> sources = PendingImage.localUris(composer.getImages().getValue());
                    if (position < sources.size()) {
                        context.startActivity(ImagePreviewActivity.intent(context, sources, position));
                    }
                }
            }

            @Override
            public void onRemove(@NonNull PendingImage image) {
                composer.removeImage(image.getId());
            }
        });
        list.setAdapter(adapter);
        composer.getImages().observe(owner, images -> {
            int before = adapter.getItemCount();
            adapter.submitList(images, () -> {
                int after = adapter.getItemCount();
                if (after > before) {
                    list.smoothScrollToPosition(after - 1);
                }
            });
        });
    }
}
