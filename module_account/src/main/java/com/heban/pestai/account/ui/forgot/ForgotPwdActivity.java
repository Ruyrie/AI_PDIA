package com.heban.pestai.account.ui.forgot;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

import com.heban.pestai.account.AccountExtras;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityForgotPwdBinding;
import com.heban.pestai.account.ui.common.FormFields;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.vo.ResetTicketVO;

/**
 * A07 找回密码 · 验证手机。入口：登录页「忘记密码？」，或 M02 设置页「修改密码」
 * （RouterPath.FORGOT_PWD + EXTRA_PHONE + EXTRA_FROM_SETTINGS，手机号预填且不可修改）。
 * 校验成功后携带 phone、reset_ticket、截止时刻启动 A08；A08 凭证过期时带回结果，本页清空验证码并提示重新获取。
 */
public class ForgotPwdActivity extends BaseActivity {

    private ActivityForgotPwdBinding binding;
    private ForgotPwdViewModel viewModel;

    private final ActivityResultLauncher<Intent> resetLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == AccountExtras.RESULT_TICKET_EXPIRED) {
                    viewModel.onTicketExpired();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_forgot_pwd);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(ForgotPwdViewModel.class);
        Intent intent = getIntent();
        viewModel.init(intent.getStringExtra(RouterPath.EXTRA_PHONE),
                intent.getBooleanExtra(RouterPath.EXTRA_FROM_SETTINGS, false));
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        if (viewModel.isFromSettings()) {
            binding.forgotToolbar.setTitle(R.string.account_change_password_title);
            binding.forgotDesc.setText(R.string.account_change_password_desc);
        }
        binding.forgotToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        FormFields.setupPhone(binding.forgotPhoneLayout);
        FormFields.setupCodeAction(binding.forgotCodeLayout, v -> viewModel.sendCode());

        viewModel.getFocusCode().observe(this, ignored -> {
            binding.forgotCodeInput.requestFocus();
            KeyboardUtils.show(binding.forgotCodeInput);
        });
        viewModel.getVerified().observe(this, this::openReset);
        viewModel.getOpenRecover().observe(this, ignored -> RouterPath.open(this, RouterPath.RECOVER_ACCOUNT));
    }

    private void openReset(@NonNull ResetTicketVO ticket) {
        Intent intent = RouterPath.intent(this, RouterPath.RESET_PWD)
                .putExtra(RouterPath.EXTRA_PHONE, viewModel.getNormalizedPhone())
                .putExtra(AccountExtras.EXTRA_RESET_TICKET, ticket.getResetTicket())
                .putExtra(AccountExtras.EXTRA_TICKET_DEADLINE, viewModel.getTicketDeadline())
                .putExtra(RouterPath.EXTRA_FROM_SETTINGS, viewModel.isFromSettings());
        resetLauncher.launch(intent);
    }
}
