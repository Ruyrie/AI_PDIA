package com.heban.pestai.account.ui.phone;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityChangePhoneBinding;
import com.heban.pestai.account.ui.common.FormFields;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;

/**
 * A10 更换手机号（RouterPath.CHANGE_PHONE）。入口：M02 设置页「手机号」行。
 * <ul>
 *     <li>输入新手机号 → 获取验证码（发往新手机号）→ 确认更换；</li>
 *     <li>成功：服务器返回的新令牌已保存（本机保持登录），页面切换为成功态（淡入 200ms，顶栏隐藏），
 *     「完成」与返回键都回到设置页，设置页通过 IUserService.userChanges() 刷新手机号。</li>
 * </ul>
 */
public class ChangePhoneActivity extends BaseActivity {

    private static final long FADE_MS = 200;

    private ActivityChangePhoneBinding binding;
    private ChangePhoneViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_change_phone);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(ChangePhoneViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        binding.changePhoneToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        FormFields.setupPhone(binding.changePhoneNewLayout);
        FormFields.setupCodeAction(binding.changePhoneCodeLayout, v -> viewModel.sendCode());

        viewModel.getFocusCode().observe(this, ignored -> {
            binding.changePhoneCodeInput.requestFocus();
            KeyboardUtils.show(binding.changePhoneCodeInput);
        });
        final boolean[] wasDone = {Boolean.TRUE.equals(viewModel.getDone().getValue())};
        viewModel.getDone().observe(this, done -> {
            boolean isDone = Boolean.TRUE.equals(done);
            if (isDone && !wasDone[0]) {
                KeyboardUtils.hide(binding.getRoot());
                View success = binding.changePhoneSuccess;
                success.setAlpha(0f);
                success.animate().alpha(1f).setDuration(FADE_MS).start();
            }
            wasDone[0] = isDone;
        });
        viewModel.getFinish().observe(this, ignored -> finish());
    }
}
