package com.heban.pestai.ai.preview;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.heban.pestai.ai.R;
import com.heban.pestai.ai.databinding.ItemAiPreviewPageBinding;

import java.util.List;

/**
 * 全屏预览的页面（ViewPager2）：每页一个 {@link ZoomImageView}。
 * Glide 按「完整显示在 2 倍屏幕尺寸内」解码（放大后仍清晰、又不按原图解码占用过多内存），不做变换（由 ZoomImageView 摆放）；
 * 加载中显示圆形进度，失败显示 image_not_supported 图标。页面回收时恢复 1 倍并释放图片。
 */
final class PreviewAdapter extends RecyclerView.Adapter<PreviewAdapter.PageHolder> {

    private final RequestManager glide;
    private final List<String> sources;
    private final int maxWidth;
    private final int maxHeight;

    PreviewAdapter(@NonNull RequestManager glide, @NonNull List<String> sources, int maxWidth, int maxHeight) {
        this.glide = glide;
        this.sources = sources;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PageHolder(ItemAiPreviewPageBinding.inflate(LayoutInflater.from(parent.getContext()), parent,
                false));
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        holder.bind(sources.get(position), position);
    }

    @Override
    public int getItemCount() {
        return sources.size();
    }

    @Override
    public void onViewRecycled(@NonNull PageHolder holder) {
        holder.binding.aiPreviewImage.resetZoom();
        glide.clear(holder.binding.aiPreviewImage);
    }

    final class PageHolder extends RecyclerView.ViewHolder {
        final ItemAiPreviewPageBinding binding;

        PageHolder(@NonNull ItemAiPreviewPageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void resetZoom() {
            binding.aiPreviewImage.resetZoom();
        }

        void bind(@NonNull String source, int position) {
            ZoomImageView image = binding.aiPreviewImage;
            image.setContentDescription(image.getContext().getString(R.string.ai_preview_image, position + 1,
                    getItemCount()));
            binding.aiPreviewProgress.setVisibility(View.VISIBLE);
            binding.aiPreviewError.setVisibility(View.GONE);
            glide.load(source)
                    .downsample(DownsampleStrategy.CENTER_INSIDE)
                    .override(maxWidth, maxHeight)
                    .dontTransform()
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model,
                                                    @NonNull Target<Drawable> target, boolean isFirstResource) {
                            binding.aiPreviewProgress.setVisibility(View.GONE);
                            binding.aiPreviewError.setVisibility(View.VISIBLE);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model,
                                                       Target<Drawable> target, @NonNull DataSource dataSource,
                                                       boolean isFirstResource) {
                            binding.aiPreviewProgress.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(image);
        }
    }
}
