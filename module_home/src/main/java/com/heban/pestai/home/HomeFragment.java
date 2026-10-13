package com.heban.pestai.home;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.base.BaseFragment;
import com.heban.pestai.base.TabReselectable;
import com.heban.pestai.common.MainViewModel;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.home.adapter.RecentAdapter;
import com.heban.pestai.home.adapter.RecentDividerDecoration;
import com.heban.pestai.home.databinding.FragmentHomeBinding;

/**
 * 主页面 Tab：H01 首页（模块04）。位于 MainActivity 中，状态栏间距由 MainActivity 统一处理；
 * 隐藏的 Tab 生命周期停在 STARTED，onResume() 只在首页可见时回调。
 */
public class HomeFragment extends BaseFragment implements TabReselectable {

    /** 必须在 Fragment 创建时注册，进入 STARTED 之后再注册会抛出 IllegalStateException */
    private final ActivityResultLauncher<PickVisualMediaRequest> pickPhoto =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onPhotoPicked);

    @Nullable
    private FragmentHomeBinding binding;
    private HomeViewModel viewModel;
    @Nullable
    private RecentAdapter adapter;
    @Nullable
    private AlertDialog loginDialog;
    /** 上次显示的日期，跨天时让列表重新绑定 */
    @Nullable
    private String shownDate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentHomeBinding b = requireBinding();
        viewModel = obtainViewModel(HomeViewModel.class);
        MainViewModel mainViewModel = obtainActivityViewModel(MainViewModel.class);
        b.setVm(viewModel);
        b.setMain(mainViewModel);
        b.setLifecycleOwner(getViewLifecycleOwner());
        observeToast(viewModel);

        // 头像文字 14sp Medium
        b.homeAvatar.getTextView().setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        b.homeRefresh.setColorSchemeColors(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary));
        b.homeRefresh.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainerLow));

        adapter = new RecentAdapter(viewModel::onRecordClick);
        b.homeRecentList.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.homeRecentList.addItemDecoration(new RecentDividerDecoration(requireContext()));
        b.homeRecentList.setAdapter(adapter);

        shownDate = null;
        viewModel.getRecentList().observe(getViewLifecycleOwner(), list -> {
            if (adapter != null) {
                adapter.submitList(list);
            }
        });
        viewModel.getDateText().observe(getViewLifecycleOwner(), date -> {
            if (shownDate != null && !shownDate.equals(date) && adapter != null) {
                adapter.rebindAll();
            }
            shownDate = date;
        });
        viewModel.getEvent().observe(getViewLifecycleOwner(), this::handleEvent);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.onVisible();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && viewModel != null) {
            viewModel.onVisible();
        }
    }

    /** 再次点击「首页」Tab：内容区平滑滚回顶部 */
    @Override
    public void onTabReselected() {
        scrollToTop();
    }

    /** 内容区平滑滚回顶部 */
    public void scrollToTop() {
        if (binding != null) {
            binding.homeScroll.smoothScrollTo(0, 0);
        }
    }

    // ---------- 事件分发 ----------

    private void handleEvent(@Nullable HomeEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type) {
            case OPEN_SCAN:
                open(RouterPath.intent(requireContext(), RouterPath.SCAN)
                        .putExtra(RouterPath.EXTRA_MODE, event.mode));
                break;
            case PICK_PHOTO:
                launchPicker();
                break;
            case OPEN_HISTORY:
                open(RouterPath.intent(requireContext(), RouterPath.HISTORY));
                break;
            case OPEN_RESULT:
                open(RouterPath.intent(requireContext(), RouterPath.SCAN_RESULT)
                        .putExtra(RouterPath.EXTRA_RECORD_ID, event.recordId));
                break;
            case OPEN_ERROR:
                open(RouterPath.intent(requireContext(), RouterPath.SCAN_ERROR)
                        .putExtra(RouterPath.EXTRA_RECORD_ID, event.recordId)
                        .putExtra(RouterPath.EXTRA_ERROR_TYPE, event.errorType)
                        .putExtra(RouterPath.EXTRA_FAIL_REASON, event.failReason)
                        .putExtra(RouterPath.EXTRA_MODE, event.mode)
                        .putExtra(RouterPath.EXTRA_IMAGE_URL, event.imageUrl));
                break;
            case NEED_LOGIN:
                showLoginDialog();
                break;
            case GO_LOGIN:
                openLogin();
                break;
            default:
                break;
        }
    }

    private void launchPicker() {
        try {
            pickPhoto.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } catch (ActivityNotFoundException e) {
            showSnackbar(getString(R.string.home_feature_unavailable));
        }
    }

    /** 选中照片后直接进入 S02 识别中；取消（uri 为 null）停留首页、不提示 */
    private void onPhotoPicked(@Nullable Uri uri) {
        if (uri == null || getContext() == null) {
            return;
        }
        Intent intent = RouterPath.intent(requireContext(), RouterPath.ANALYZING)
                .putExtra(RouterPath.EXTRA_IMAGE_URI, uri.toString())
                .putExtra(RouterPath.EXTRA_MODE, RouterPath.MODE_PEST)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // 读取授权只作用于 Intent 的 data / ClipData；不设置 data，以免影响 action 匹配
        intent.setClipData(ClipData.newRawUri(null, uri));
        open(intent);
    }

    private void showLoginDialog() {
        if (loginDialog != null && loginDialog.isShowing()) {
            return;
        }
        loginDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_login_dialog_title)
                .setNegativeButton(com.heban.pestai.base.R.string.pest_cancel, null)
                .setPositiveButton(R.string.home_login_dialog_confirm, (dialog, which) -> openLogin())
                .show();
    }

    private void openLogin() {
        open(RouterPath.intent(requireContext(), RouterPath.LOGIN));
    }

    /** 目标页面所在组件未集成时（组件单独运行）隐式 Intent 找不到页面，提示而不崩溃 */
    private void open(@NonNull Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            showSnackbar(getString(R.string.home_feature_unavailable));
        }
    }

    @NonNull
    private FragmentHomeBinding requireBinding() {
        if (binding == null) {
            throw new IllegalStateException("HomeFragment 视图尚未创建");
        }
        return binding;
    }

    @Override
    public void onDestroyView() {
        if (loginDialog != null) {
            loginDialog.dismiss();
            loginDialog = null;
        }
        if (binding != null) {
            binding.homeRecentList.setAdapter(null);
        }
        adapter = null;
        binding = null;
        super.onDestroyView();
    }
}
