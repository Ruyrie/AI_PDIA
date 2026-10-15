package com.heban.pestai.ai.compose;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.RequestManager;
import com.heban.pestai.ai.R;
import com.heban.pestai.ai.databinding.ItemAiPendingImageBinding;
import com.heban.pestai.base.util.RxClick;

import java.util.List;
import java.util.Objects;

/**
 * 输入栏上方的待发送图片缩略图（ViewBinding）。
 * 同一张图片只是状态变化时只更新遮罩、进度与无障碍描述，本地图片不重新加载；
 * 张数变化后重新绑定全部项，使「第 N 张图片」的序号保持正确。
 */
final class PendingImageAdapter extends ListAdapter<PendingImage, PendingImageAdapter.Holder> {

    /** 只更新序号 / 状态，不重新加载图片 */
    private static final Object PAYLOAD_STATE = new Object();

    interface Listener {
        /** 点按缩略图（失败时重试，完成时预览） */
        void onImageClick(@NonNull PendingImage image, int position);

        void onRemove(@NonNull PendingImage image);
    }

    private static final DiffUtil.ItemCallback<PendingImage> DIFF = new DiffUtil.ItemCallback<PendingImage>() {
        @Override
        public boolean areItemsTheSame(@NonNull PendingImage oldItem, @NonNull PendingImage newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull PendingImage oldItem, @NonNull PendingImage newItem) {
            return oldItem.equals(newItem);
        }

        @Nullable
        @Override
        public Object getChangePayload(@NonNull PendingImage oldItem, @NonNull PendingImage newItem) {
            return PAYLOAD_STATE;
        }
    };

    private final RequestManager glide;
    private final Listener listener;

    PendingImageAdapter(@NonNull RequestManager glide, @NonNull Listener listener) {
        super(DIFF);
        this.glide = glide;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemAiPendingImageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position), position);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        // bind 本身只在图片源变化时重新加载
        holder.bind(getItem(position), position);
    }

    @Override
    public void onCurrentListChanged(@NonNull List<PendingImage> previousList,
                                     @NonNull List<PendingImage> currentList) {
        if (previousList.size() != currentList.size() && !currentList.isEmpty()) {
            // 增删后其余项位置变化但内容没变，DiffUtil 不会重新绑定：刷新序号
            notifyItemRangeChanged(0, currentList.size(), PAYLOAD_STATE);
        }
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final ItemAiPendingImageBinding binding;
        @Nullable
        private PendingImage image;
        @Nullable
        private String boundSource;

        Holder(@NonNull ItemAiPendingImageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.aiPendingFrame.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (image != null && position != RecyclerView.NO_POSITION) {
                    listener.onImageClick(image, position);
                }
            });
            // 防连点：移除后后一张会移到手指下方
            RxClick.throttle(binding.aiPendingRemove, v -> {
                if (image != null && getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                    listener.onRemove(image);
                }
            });
        }

        void bind(@NonNull PendingImage item, int position) {
            image = item;
            String source = item.getLocalUri();
            if (!Objects.equals(source, boundSource)) {
                boundSource = source;
                glide.load(source).centerCrop().into(binding.aiPendingImage);
            }
            boolean busy = item.isBusy();
            boolean failed = item.isFailed();
            binding.aiPendingOverlay.setVisibility(busy || failed ? View.VISIBLE : View.GONE);
            binding.aiPendingProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
            binding.aiPendingFailed.setVisibility(failed ? View.VISIBLE : View.GONE);
            // 上传中不可点；失败点按重试，完成点按预览
            binding.aiPendingFrame.setClickable(!busy);

            Context context = binding.getRoot().getContext();
            int number = position + 1;
            binding.aiPendingFrame.setContentDescription(context.getString(R.string.ai_queue_image, number));
            String state = null;
            if (busy) {
                state = context.getString(R.string.ai_queue_state_uploading);
            } else if (failed) {
                state = context.getString(R.string.ai_queue_state_failed);
            }
            binding.aiPendingFrame.setStateDescription(state);
            binding.aiPendingRemove.setContentDescription(context.getString(R.string.ai_queue_remove, number));
        }
    }
}
