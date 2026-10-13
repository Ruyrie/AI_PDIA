package com.heban.pestai.account.ui.register;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityRegisterBinding;
import com.heban.pestai.account.ui.common.FormFields;
import com.heban.pestai.account.ui.common.LinkText;
import com.heban.pestai.account.ui.login.LoginEvent;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;

/**
 * A05 注册（第 1 步）。入口：登录页「立即注册」或引导页「开始使用」。注册成功后进入 A06 并关闭自身。
 */
public class RegisterActivity extends BaseActivity {

    private ActivityRegisterBinding binding;
    private RegisterViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_register);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(RegisterViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        binding.registerToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        FormFields.setupPhone(binding.registerPhoneLayout);
        FormFields.setupCodeAction(binding.registerCodeLayout, v -> viewModel.sendCode());
        LinkText.bindAgreement(binding.registerAgreementText);
        RxClick.throttle(binding.registerGoLogin, v -> goLogin());

        viewModel.getAgreementShake().observe(this, ignored -> FormFields.shake(binding.registerAgreementRow));
        viewModel.getFocusCode().observe(this, ignored -> {
            binding.registerCodeInput.requestFocus();
            KeyboardUtils.show(binding.registerCodeInput);
        });
        viewModel.getResult().observe(this, event -> {
            if (event == LoginEvent.GO_PROFILE_SETUP) {
                startActivity(RouterPath.intent(this, RouterPath.PROFILE_SETUP));
                finish();
            }
        });
    }

    /** 「已有账号？去登录」：从登录页进入时直接返回，从引导页进入（本页是任务根）时打开登录页 */
    private void goLogin() {
        if (isTaskRoot()) {
            RouterPath.open(this, RouterPath.LOGIN);
        }
        finish();
    }
}
