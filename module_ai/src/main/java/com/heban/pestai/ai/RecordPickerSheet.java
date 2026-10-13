package com.heban.pestai.ai;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.color.MaterialColors;
import com.heban.pestai.ai.databinding.DialogAiRecordPickerBinding;
import com.heban.pestai.ai.databinding.ItemAiRecordChoiceBinding;
import com.heban.pestai.ai.util.AiFormat;
import com.heban.pestai.base.AppHost;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.common.format.RecordTag;
import com.heban.pestai.common.vo.RecordItemVO;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Q01「追问结果」卡片弹出的底部面板（顶部圆角 28dp）「选择要追问的识别记录」：
 * 最近 10 条中识别成功的记录（48dp 圆角缩略图、名称、「病虫害 · 匹配度 92% · 今天 14:20」、严重程度标签），
 * 点一条打开 Q02 追问该记录；没有记录时显示「还没有识别记录」+「去拍照识别」；加载失败可重试。
 * 数据来自 AskAiFragment 的 ViewModel（作为其子 Fragment 显示，旋转屏幕后自动恢复）。
 */
public class RecordPickerSheet extends BottomSheetDialogFragment {

    @Nullable
    private DialogAiRecordPickerBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DialogAiRecordPickerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        DialogAiRecordPickerBinding b = Objects.requireNonNull(binding);
        AskAiViewModel viewModel = new ViewModelProvider(requireParentFragment(),
                AppHost.from(requireContext()).getViewModelFactory()).get(AskAiViewModel.class);
        b.setLifecycleOwner(getViewLifecycleOwner());
        b.setVm(viewModel);

        ChoiceAdapter adapter = new ChoiceAdapter(record -> {
            dismiss();
            viewModel.pickRecord(record);
        });
        b.aiPickerList.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.aiPickerList.setAdapter(adapter);
        viewModel.getRecordChoices().observe(getViewLifecycleOwner(), adapter::submitList);
        b.aiPickerScan.setOnClickListener(v -> {
            dismiss();
            viewModel.openScan();
        });
        b.aiPickerRetry.setOnClickListener(v -> viewModel.loadRecordChoices());
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.aiPickerList.setAdapter(null);
        }
        binding = null;
        super.onDestroyView();
    }

    private interface OnPick {
        void onPick(@NonNull RecordItemVO record);
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

    private static final class ChoiceAdapter extends ListAdapter<RecordItemVO, ChoiceHolder> {
        private final OnPick onPick;

        ChoiceAdapter(@NonNull OnPick onPick) {
            super(DIFF);
            this.onPick = onPick;
        }

        @NonNull
        @Override
        public ChoiceHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemAiRecordChoiceBinding b = ItemAiRecordChoiceBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            ChoiceHolder holder = new ChoiceHolder(b);
            b.getRoot().setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    onPick.onPick(getItem(position));
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull ChoiceHolder holder, int position) {
            holder.bind(getItem(position));
        }
    }

    private static final class ChoiceHolder extends RecyclerView.ViewHolder {
        private final ItemAiRecordChoiceBinding binding;
        private final int radius;

        ChoiceHolder(@NonNull ItemAiRecordChoiceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            this.radius = DimenUtils.dp(binding.getRoot().getContext(), 12);
        }

        @SuppressWarnings("unchecked") // Glide transform(Transformation<Bitmap>...) 的泛型可变参数
        void bind(@NonNull RecordItemVO record) {
            binding.aiChoiceName.setText(AiFormat.recordName(record));
            binding.aiChoiceSubtitle.setText(AiFormat.recordSubtitle(record, LocalDateTime.now()));
            bindTag(binding.aiChoiceTag, RecordTag.of(record));
            Glide.with(binding.aiChoiceThumb)
                    .load(record.getImageUrl())
                    .placeholder(com.heban.pestai.base.R.drawable.pest_img_placeholder)
                    .fallback(com.heban.pestai.base.R.drawable.pest_img_placeholder)
                    .error(com.heban.pestai.base.R.drawable.pest_img_error)
                    .transform(new CenterCrop(), new RoundedCorners(radius))
                    .into(binding.aiChoiceThumb);
        }

        private static void bindTag(@NonNull TextView view, @Nullable RecordTag tag) {
            if (tag == null) {
                view.setVisibility(View.GONE);
                return;
            }
            view.setVisibility(View.VISIBLE);
            view.setText(tag.text);
            view.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(view, tag.bgAttr)));
            view.setTextColor(MaterialColors.getColor(view, tag.fgAttr));
        }
    }
}
