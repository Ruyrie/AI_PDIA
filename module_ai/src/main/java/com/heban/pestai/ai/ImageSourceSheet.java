package com.heban.pestai.ai;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.heban.pestai.ai.databinding.DialogAiImageSourceBinding;
import com.heban.pestai.base.AppHost;

/**
 * Q01「带图提问」卡片弹出的底部面板（顶部圆角 28dp）：「拍照」「从相册选择」「取消」。
 * 作为 AskAiFragment 的子 Fragment 显示（旋转屏幕后自动恢复），选择后交给 AskAiFragment 的 ViewModel，
 * 由 AskAiFragment 申请相机权限、打开系统相机或照片选择器。
 */
public class ImageSourceSheet extends BottomSheetDialogFragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return DialogAiImageSourceBinding.inflate(inflater, container, false).getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        DialogAiImageSourceBinding binding = DialogAiImageSourceBinding.bind(view);
        AskAiViewModel viewModel = parentViewModel();
        binding.aiSourceCamera.setOnClickListener(v -> {
            dismiss();
            viewModel.onTakePhoto();
        });
        binding.aiSourceAlbum.setOnClickListener(v -> {
            dismiss();
            viewModel.onPickImageClick();
        });
        binding.aiSourceCancel.setOnClickListener(v -> dismiss());
    }

    @NonNull
    private AskAiViewModel parentViewModel() {
        return new ViewModelProvider(requireParentFragment(),
                AppHost.from(requireContext()).getViewModelFactory()).get(AskAiViewModel.class);
    }
}
