package com.heban.pestai.mine.ui.about;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.mine.R;
import com.heban.pestai.mine.databinding.ActivityAboutBinding;
import com.heban.pestai.mine.ui.MineUi;

/**
 * M03 关于（RouterPath.ABOUT）：版本信息、检查更新（v1.0 功能尚未开放）、用户协议、隐私政策。
 */
public class AboutActivity extends BaseActivity {

    private AboutViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityAboutBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_about);
        viewModel = obtainViewModel(AboutViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        InsetsHelper.applySystemBars(binding.aboutRoot);
        binding.aboutToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        observeToast(viewModel);
        viewModel.getEvent().observe(this, this::onEvent);
    }

    private void onEvent(@Nullable AboutEvent event) {
        if (event == null) {
            return;
        }
        switch (event) {
            case NOT_OPEN:
                MineUi.showSnackbar(this, getString(R.string.mine_not_open), com.heban.pestai.base.R.drawable.ic_schedule);
                break;
            case OPEN_USER_AGREEMENT:
                openAgreement(RouterPath.AGREEMENT_USER);
                break;
            case OPEN_PRIVACY:
                openAgreement(RouterPath.AGREEMENT_PRIVACY);
                break;
            default:
                break;
        }
    }

    private void openAgreement(String type) {
        MineUi.start(this, RouterPath.intent(this, RouterPath.AGREEMENT)
                .putExtra(RouterPath.EXTRA_AGREEMENT_TYPE, type));
    }
}
