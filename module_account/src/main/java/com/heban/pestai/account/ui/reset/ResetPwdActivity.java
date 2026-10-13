package com.heban.pestai.account.ui.reset;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

import com.heban.pestai.account.AccountExtras;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityResetPwdBinding;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.common.router.RouterPath;

/**
 * A08 设置新密码。
 * <ul>
 *     <li>凭证过期（本地截止时刻已过，或后端返回凭证无效）：以 RESULT_TICKET_EXPIRED 返回 A07；</li>
 *     <li>成功：切换为成功态（淡入 200ms，顶栏隐藏），成功态下返回键等同「去登录」；</li>
 *     <li>去登录：普通找回流程用 CLEAR_TOP + NEW_TASK 回到 A03；从设置页进入（栈中没有登录页）用 NEW_TASK + CLEAR_TASK。</li>
 * </ul>
 */
public class ResetPwdActivity extends BaseActivity {

    private static final long FADE_MS = 200;

    private ActivityResetPwdBinding binding;
    private ResetPwdViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_reset_pwd);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(ResetPwdViewModel.class);
        Intent intent = getIntent();
        viewModel.init(intent.getStringExtra(RouterPath.EXTRA_PHONE),
                intent.getStringExtra(AccountExtras.EXTRA_RESET_TICKET),
                intent.getLongExtra(AccountExtras.EXTRA_TICKET_DEADLINE, 0L),
                intent.getBooleanExtra(RouterPath.EXTRA_FROM_SETTINGS, false));
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        binding.resetToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        binding.resetPasswordLayout.setEndIconOnClickListener(v -> viewModel.togglePasswordVisible());

        OnBackPressedCallback successBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                viewModel.goLogin();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, successBack);

        final boolean[] wasDone = {Boolean.TRUE.equals(viewModel.getDone().getValue())};
        viewModel.getDone().observe(this, done -> {
            boolean isDone = Boolean.TRUE.equals(done);
            successBack.setEnabled(isDone);
            if (isDone && !wasDone[0]) {
                KeyboardUtils.hide(binding.getRoot());
                View success = binding.resetSuccess;
                success.setAlpha(0f);
                success.animate().alpha(1f).setDuration(FADE_MS).start();
            }
            wasDone[0] = isDone;
        });
        viewModel.getExpired().observe(this, message -> {
            setResult(AccountExtras.RESULT_TICKET_EXPIRED);
            finish();
        });
        viewModel.getNavigateLogin().observe(this, this::goLogin);
    }

    private void goLogin(@Nullable Boolean fromSettings) {
        Intent intent = RouterPath.intent(this, RouterPath.LOGIN);
        if (Boolean.TRUE.equals(fromSettings)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        startActivity(intent);
        finish();
    }
}
