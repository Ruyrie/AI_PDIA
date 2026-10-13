package com.heban.pestai.record.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.heban.pestai.record.BR;
import com.heban.pestai.record.R;
import com.heban.pestai.record.databinding.ItemRecordBinding;
import com.heban.pestai.record.databinding.ItemRecordFailedBinding;
import com.heban.pestai.record.databinding.ItemRecordFooterBinding;
import com.heban.pestai.record.databinding.ItemRecordHeaderBinding;
import com.heban.pestai.record.widget.SwipeItemLayout;

/**
 * 识别记录列表：ListAdapter + DiffUtil（后台线程比较，只刷新变化的行并带动画），
 * 4 种 ViewType（日期标题 / 记录行 / 失败记录行 / 底部提示）。点击、删除、重试回调给 HistoryActivity。
 */
public class HistoryAdapter extends ListAdapter<HistoryRow, HistoryAdapter.Holder> {

    public interface Callback {
        /** 点按记录内容（只有未被 SwipeItemLayout 拦截的轻点才会到达） */
        void onRecordClick(@NonNull HistoryRow row);

        /** 点按左滑露出的「删除」 */
        void onDeleteClick(@NonNull HistoryRow row);

        /** 点按底部提示行（加载失败时重试） */
        void onFooterClick(@NonNull HistoryRow row);
    }

    private static final DiffUtil.ItemCallback<HistoryRow> DIFF = new DiffUtil.ItemCallback<HistoryRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull HistoryRow oldItem, @NonNull HistoryRow newItem) {
            return oldItem.getKey().equals(newItem.getKey());
        }

        @Override
        public boolean areContentsTheSame(@NonNull HistoryRow oldItem, @NonNull HistoryRow newItem) {
            return oldItem.equals(newItem);
        }
    };

    private final Callback callback;

    public HistoryAdapter(@NonNull Callback callback) {
        super(DIFF);
        this.callback = callback;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case HistoryRow.TYPE_HEADER:
                return new Holder(ItemRecordHeaderBinding.inflate(inflater, parent, false), null, null);
            case HistoryRow.TYPE_RECORD: {
                ItemRecordBinding binding = ItemRecordBinding.inflate(inflater, parent, false);
                Holder holder = new Holder(binding, binding.recordSwipe, binding.recordThumb);
                bindRecordActions(holder, binding.recordContent, binding.recordBtnDelete);
                return holder;
            }
            case HistoryRow.TYPE_FAILED: {
                ItemRecordFailedBinding binding = ItemRecordFailedBinding.inflate(inflater, parent, false);
                Holder holder = new Holder(binding, binding.recordSwipe, null);
                bindRecordActions(holder, binding.recordContent, binding.recordBtnDelete);
                return holder;
            }
            case HistoryRow.TYPE_FOOTER:
            default: {
                ItemRecordFooterBinding binding = ItemRecordFooterBinding.inflate(inflater, parent, false);
                Holder holder = new Holder(binding, null, null);
                binding.getRoot().setOnClickListener(v -> {
                    HistoryRow row = rowAt(holder);
                    if (row != null) {
                        callback.onFooterClick(row);
                    }
                });
                return holder;
            }
        }
    }

    private void bindRecordActions(@NonNull Holder holder, @NonNull View content, @NonNull View delete) {
        content.setOnClickListener(v -> {
            HistoryRow row = rowAt(holder);
            if (row != null) {
                callback.onRecordClick(row);
            }
        });
        delete.setOnClickListener(v -> onDelete(holder));
        // 无障碍：读屏用户无法左滑，提供「删除」自定义操作
        ViewCompat.addAccessibilityAction(content, content.getContext().getString(R.string.record_delete),
                (view, arguments) -> {
                    onDelete(holder);
                    return true;
                });
    }

    private void onDelete(@NonNull Holder holder) {
        HistoryRow row = rowAt(holder);
        if (row == null) {
            return;
        }
        if (holder.swipe != null) {
            holder.swipe.resetImmediately();
        }
        callback.onDeleteClick(row);
    }

    @Nullable
    private HistoryRow rowAt(@NonNull Holder holder) {
        int position = holder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION || position >= getItemCount()) {
            return null;
        }
        return getItem(position);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        HistoryRow row = getItem(position);
        holder.binding.setVariable(BR.row, row);
        holder.binding.executePendingBindings();
        if (holder.swipe != null) {
            holder.swipe.resetImmediately();
            holder.swipe.setCorners(row.isFirst(), row.isLast());
        }
        if (row.getType() == HistoryRow.TYPE_FOOTER) {
            holder.itemView.setClickable(row.getFooterState() == HistoryRow.FOOTER_FAILED);
        }
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        super.onViewRecycled(holder);
        if (holder.thumb != null && !isDestroyed(holder.thumb.getContext())) {
            // 清除该缩略图上的请求和图片，快速滚动时不会把旧图片显示到新的记录上
            Glide.with(holder.thumb).clear(holder.thumb);
        }
    }

    private static boolean isDestroyed(@NonNull Context context) {
        Context c = context;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                return ((Activity) c).isDestroyed();
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return false;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ViewDataBinding binding;
        @Nullable
        final SwipeItemLayout swipe;
        @Nullable
        final ImageView thumb;

        Holder(@NonNull ViewDataBinding binding, @Nullable SwipeItemLayout swipe, @Nullable ImageView thumb) {
            super(binding.getRoot());
            this.binding = binding;
            this.swipe = swipe;
            this.thumb = thumb;
        }
    }
}
