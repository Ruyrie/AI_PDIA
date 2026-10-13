package com.heban.pestai.home.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.home.databinding.ItemHomeRecentBinding;

/**
 * 首页最近识别列表（最多 3 条）：ListAdapter + DiffUtil，只刷新变化的行；布局直接绑定 RecordItemVO。
 */
public class RecentAdapter extends ListAdapter<RecordItemVO, RecentAdapter.ViewHolder> {

    public interface OnRecordClickListener {
        void onRecordClick(@NonNull RecordItemVO item);
    }

    private static final DiffUtil.ItemCallback<RecordItemVO> DIFF = new DiffUtil.ItemCallback<RecordItemVO>() {
        @Override
        public boolean areItemsTheSame(@NonNull RecordItemVO oldItem, @NonNull RecordItemVO newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull RecordItemVO oldItem, @NonNull RecordItemVO newItem) {
            return oldItem.equals(newItem);
        }
    };

    @NonNull
    private final OnRecordClickListener listener;

    public RecentAdapter(@NonNull OnRecordClickListener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHomeRecentBinding binding = ItemHomeRecentBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        ViewHolder holder = new ViewHolder(binding);
        binding.getRoot().setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION || position >= getItemCount()) {
                return;
            }
            listener.onRecordClick(getItem(position));
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.setItem(getItem(position));
        holder.binding.executePendingBindings();
    }

    /** 跨天后全部行重新绑定，副标题的「今天 / 昨天」按新日期显示 */
    public void rebindAll() {
        if (getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {

        final ItemHomeRecentBinding binding;

        ViewHolder(@NonNull ItemHomeRecentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
