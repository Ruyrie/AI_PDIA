package com.heban.pestai.mine;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.base.BaseFragment;
import com.heban.pestai.common.MainViewModel;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.mine.databinding.FragmentMineBinding;
import com.heban.pestai.mine.ui.MineEvent;
import com.heban.pestai.mine.ui.MineUi;
import com.heban.pestai.mine.ui.MineViewModel;

/**
 * 主页面 Tab：我的（M01，模块09）。由壳工程 MainActivity 承载：
 * <ul>
 *     <li>顶部状态栏间距由 MainActivity 统一处理，这里不再添加。</li>
 *     <li>隐藏的 Tab 生命周期停在 STARTED，onResume() 只在可见时回调；记录 / 资料变化只标记 dirty，可见时再刷新。</li>
 *     <li>「AI 问答」通过 Activity 作用域的 MainViewModel 切换 Tab，不引用壳工程类。</li>
 *     <li>点资料区（头像、昵称、手机号）：已登录打开 A06 编辑资料（RouterPath.PROFILE_SETUP + EXTRA_PROFILE_MODE = edit），
 *     保存后 userChanges() 带回新资料；游客打开登录页。</li>
 *     <li>资料区下方「你还没有保存账号恢复码」提醒卡片：每次可见时刷新，「去生成」打开 A12（RouterPath.RECOVERY_CODE）。</li>
 * </ul>
 */
public class MineFragment extends BaseFragment {

    private FragmentMineBinding binding;
    private MineViewModel viewModel;
    @Nullable
    private IRecordService recordService;

    /** 保存为成员字段：注册与移除必须是同一个对象 */
    private final IRecordService.OnRecordChangedListener recordListener = () -> {
        if (viewModel != null) {
            viewModel.markDirty();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMineBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = obtainViewModel(MineViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(getViewLifecycleOwner());
        // 头像首字 30sp Medium
        binding.mineAvatar.getTextView().setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));

        observeToast(viewModel);
        viewModel.getEvent().observe(getViewLifecycleOwner(), this::onEvent);

        recordService = ServiceHub.get(IRecordService.class);
        recordService.addOnChangedListener(recordListener);
        ServiceHub.get(IUserService.class).userChanges()
                .observe(getViewLifecycleOwner(), viewModel::onUserChanged);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Activity 恢复时被隐藏的 Tab 理论上也可能回调，先判断是否可见
        if (!isHidden()) {
            viewModel.refreshIfNeeded();
            // 每次可见都刷新恢复码提醒（从 A12 生成后返回即消失）
            viewModel.refreshRecoveryReminder();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isResumed() && viewModel != null) {
            viewModel.refreshIfNeeded();
            viewModel.refreshRecoveryReminder();
        }
    }

    @Override
    public void onDestroyView() {
        if (recordService != null) {
            recordService.removeOnChangedListener(recordListener);
            recordService = null;
        }
        binding = null;
        super.onDestroyView();
    }

    private void onEvent(@Nullable MineEvent event) {
        if (event == null) {
            return;
        }
        switch (event) {
            case GO_SCAN:
                MineUi.open(requireContext(), RouterPath.SCAN);
                break;
            case GO_HISTORY:
                MineUi.open(requireContext(), RouterPath.HISTORY);
                break;
            case GO_HISTORY_PEST:
                MineUi.start(requireContext(), RouterPath.intent(requireContext(), RouterPath.HISTORY)
                        .putExtra(RouterPath.EXTRA_MODE, RouterPath.MODE_PEST));
                break;
            case GO_AI_TAB:
                obtainActivityViewModel(MainViewModel.class).selectTab(MainViewModel.TAB_AI);
                break;
            case GO_SETTINGS:
                MineUi.open(requireContext(), RouterPath.SETTINGS);
                break;
            case GO_ABOUT:
                MineUi.open(requireContext(), RouterPath.ABOUT);
                break;
            case GO_LOGIN:
                MineUi.open(requireContext(), RouterPath.LOGIN);
                break;
            case GO_EDIT_PROFILE:
                MineUi.start(requireContext(), RouterPath.intent(requireContext(), RouterPath.PROFILE_SETUP)
                        .putExtra(RouterPath.EXTRA_PROFILE_MODE, RouterPath.PROFILE_MODE_EDIT));
                break;
            case GO_RECOVERY_CODE:
                MineUi.open(requireContext(), RouterPath.RECOVERY_CODE);
                break;
            case NEED_LOGIN:
                MineUi.showNeedLogin(requireContext());
                break;
            default:
                break;
        }
    }
}
