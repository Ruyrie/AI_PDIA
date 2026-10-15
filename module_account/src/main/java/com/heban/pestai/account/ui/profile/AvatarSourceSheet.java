package com.heban.pestai.account.ui.profile;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.heban.pestai.account.databinding.DialogAccountAvatarSourceBinding;

/**
 * A06 完善资料 / 编辑资料点头像弹出的底部面板：「拍照」「从相册选择」「取消」。
 * 不依赖宿主：选择结果用 Fragment Result API 交给显示它的 FragmentManager（{@link #REQUEST_KEY}，
 * 结果中的 {@link #RESULT_SOURCE} 为 {@link #SOURCE_CAMERA} / {@link #SOURCE_ALBUM}）；取消不发出结果。
 * 旋转屏幕后面板与宿主的监听都会自动恢复。
 */
public class AvatarSourceSheet extends BottomSheetDialogFragment {

    public static final String REQUEST_KEY = "account_avatar_source";
    public static final String RESULT_SOURCE = "source";
    public static final int SOURCE_CAMERA = 1;
    public static final int SOURCE_ALBUM = 2;

    private static final String TAG = "account_avatar_source";

    /** 在指定 FragmentManager 上显示（已显示或状态已保存时忽略） */
    public static void show(@NonNull FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) == null && !fm.isStateSaved()) {
            new AvatarSourceSheet().show(fm, TAG);
        }
    }

    /** 从结果中取出选择的来源：{@link #SOURCE_CAMERA} / {@link #SOURCE_ALBUM} */
    public static int sourceOf(@NonNull Bundle result) {
        return result.getInt(RESULT_SOURCE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return DialogAccountAvatarSourceBinding.inflate(inflater, container, false).getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        DialogAccountAvatarSourceBinding binding = DialogAccountAvatarSourceBinding.bind(view);
        binding.accountSourceCamera.setOnClickListener(v -> choose(SOURCE_CAMERA));
        binding.accountSourceAlbum.setOnClickListener(v -> choose(SOURCE_ALBUM));
        binding.accountSourceCancel.setOnClickListener(v -> dismiss());
    }

    private void choose(int source) {
        if (isStateSaved()) {
            return;
        }
        Bundle result = new Bundle();
        result.putInt(RESULT_SOURCE, source);
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
        dismiss();
    }
}
