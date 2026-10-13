package com.heban.pestai.account.ui.recover;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.button.MaterialButton;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityRecoverAccountBinding;
import com.heban.pestai.account.ui.common.FormFields;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.common.constant.RecoverMethod;
import com.heban.pestai.common.router.RouterPath;

/**
 * A09 找回账号（RouterPath.RECOVER_ACCOUNT）。入口：A07「手机号已停用、收不到验证码？　找回账号 ›」提示条。
 * <ul>
 *     <li>第 2 步与「无法验证身份」时，顶栏返回与系统返回键回到第 1 步（已填内容保留）；</li>
 *     <li>成功：仓库已保存新令牌（自动登录），成功态淡入；「进入首页」与返回键都以清空任务栈方式进入 RouterPath.MAIN；</li>
 *     <li>「返回登录」：CLEAR_TOP + NEW_TASK 回到登录页（A07、A09 出栈）；「用新手机号注册」打开 A05。</li>
 * </ul>
 */
public class RecoverAccountActivity extends BaseActivity {

    private static final long FADE_MS = 200;

    private ActivityRecoverAccountBinding binding;
    private RecoverAccountViewModel viewModel;
    private int lastStep;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_recover_account);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(RecoverAccountViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        FormFields.setupPhone(binding.recoverOldPhoneLayout);
        FormFields.setupPhone(binding.recoverNewPhoneLayout);
        FormFields.setupRecoveryCode(binding.recoverCodeLayout);
        FormFields.setupCodeAction(binding.recoverSmsLayout, v -> viewModel.sendCode());
        binding.recoverPasswordLayout.setEndIconOnClickListener(v -> viewModel.togglePasswordVisible());
        binding.recoverNewPasswordLayout.setEndIconOnClickListener(v -> viewModel.toggleNewPasswordVisible());
        binding.recoverConfirmLayout.setEndIconOnClickListener(v -> viewModel.toggleConfirmVisible());
        setupMethod();

        OnBackPressedCallback stepBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                viewModel.back();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, stepBack);
        binding.recoverToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        Integer initial = viewModel.getStep().getValue();
        lastStep = initial == null ? RecoverAccountViewModel.STEP_VERIFY : initial;
        viewModel.getStep().observe(this, value -> {
            int step = value == null ? RecoverAccountViewModel.STEP_VERIFY : value;
            stepBack.setEnabled(step != RecoverAccountViewModel.STEP_VERIFY);
            if (step != lastStep) {
                binding.recoverScroll.scrollTo(0, 0);
                if (step == RecoverAccountViewModel.STEP_DONE) {
                    KeyboardUtils.hide(binding.getRoot());
                    View success = binding.recoverSuccess;
                    success.setAlpha(0f);
                    success.animate().alpha(1f).setDuration(FADE_MS).start();
                } else if (step == RecoverAccountViewModel.STEP_UNVERIFIABLE) {
                    KeyboardUtils.hide(binding.getRoot());
                }
            }
            lastStep = step;
        });
        viewModel.getFocusCode().observe(this, ignored -> {
            binding.recoverSmsInput.requestFocus();
            KeyboardUtils.show(binding.recoverSmsInput);
        });
        viewModel.getNavigateMain().observe(this, ignored -> {
            RouterPath.openClearTask(this, RouterPath.MAIN);
            finish();
        });
        viewModel.getNavigateLogin().observe(this, ignored -> {
            Intent intent = RouterPath.intent(this, RouterPath.LOGIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });
        viewModel.getNavigateRegister().observe(this, ignored -> RouterPath.open(this, RouterPath.REGISTER));
    }

    /** 验证方式分段按钮：选中项带 check 图标（与 A06 种植身份一致） */
    private void setupMethod() {
        binding.recoverMethodGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            MaterialButton button = group.findViewById(checkedId);
            if (button == null) {
                return;
            }
            // 每个按钮使用独立的 Drawable 实例，避免共享 bounds / tint
            Drawable check = isChecked
                    ? ContextCompat.getDrawable(this, com.heban.pestai.base.R.drawable.ic_check)
                    : null;
            button.setIcon(check);
            if (isChecked) {
                viewModel.selectMethod(methodOf(checkedId));
            }
        });
        viewModel.getMethod().observe(this, value -> {
            int id = RecoverMethod.RECOVERY_CODE.equals(value)
                    ? R.id.recover_method_code
                    : R.id.recover_method_password;
            if (binding.recoverMethodGroup.getCheckedButtonId() != id) {
                binding.recoverMethodGroup.check(id);
            }
        });
    }

    @NonNull
    private static String methodOf(int buttonId) {
        return buttonId == R.id.recover_method_code ? RecoverMethod.RECOVERY_CODE : RecoverMethod.PASSWORD;
    }
}
