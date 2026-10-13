package com.heban.pestai.recognize.ui.result;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.heban.pestai.common.vo.CandidateVO;
import com.heban.pestai.recognize.databinding.ItemScanOtherBinding;

import java.util.Objects;

/**
 * R01「其他可能」列表：每行直接绑定一个 CandidateVO（名称、小进度条、百分比），仅展示、不可点击。
 * DiffUtil 以 name 判断是否为同一项，以 name 与 confidence 判断内容是否相同，重新识别后只刷新变化的行。
 */
public class OtherAdapter extends ListAdapter<CandidateVO, OtherAdapter.Holder> {

    private static final DiffUtil.ItemCallback<CandidateVO> DIFF = new DiffUtil.ItemCallback<CandidateVO>() {
        @Override
        public boolean areItemsTheSame(@NonNull CandidateVO oldItem, @NonNull CandidateVO newItem) {
            return Objects.equals(oldItem.getName(), newItem.getName());
        }

        @Override
        public boolean areContentsTheSame(@NonNull CandidateVO oldItem, @NonNull CandidateVO newItem) {
            return oldItem.equals(newItem);
        }
    };

    public OtherAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemScanOtherBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.binding.setItem(getItem(position));
        holder.binding.executePendingBindings();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemScanOtherBinding binding;

        Holder(@NonNull ItemScanOtherBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
