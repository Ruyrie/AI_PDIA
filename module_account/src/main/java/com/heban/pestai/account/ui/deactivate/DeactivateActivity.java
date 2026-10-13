package com.heban.pestai.account.ui.deactivate;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.account.AccountExtras;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityDeactivateBinding;
import com.heban.pestai.account.ui.common.FormFields;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.common.router.RouterPath;

/**
 * A11 注销账号（RouterPath.DEACTIVATE）。入口：M02 设置页「注销账号」。
 * <ul>
 *     <li>获取验证码（服务器发往当前手机号）→ 输入验证码 → 勾选「我已了解注销后果」→「注销账号」→ 二次确认；</li>
 *     <li>成功：ViewModel 已调用 IUserService.logout() 清除本地登录态，这里以清空任务栈方式打开登录页，
 *     并带 Intent 布尔参数 account_deactivated，登录页显示「账号已注销」。</li>
 * </ul>
 */
public class DeactivateActivity extends BaseActivity {

    private ActivityDeactivateBinding binding;
    private DeactivateViewModel viewModel;
    @Nullable
    private AlertDialog confirmDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_deactivate);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(DeactivateViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        binding.deactivateToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        FormFields.setupCodeAction(binding.deactivateCodeLayout, v -> viewModel.sendCode());
        binding.deactivateAgreeText.setOnClickListener(v -> binding.deactivateAgree.toggle());

        viewModel.getFocusCode().observe(this, ignored -> {
            binding.deactivateCodeInput.requestFocus();
            KeyboardUtils.show(binding.deactivateCodeInput);
        });
        viewModel.getShowConfirm().observe(this, ignored -> showConfirmDialog());
        viewModel.getDeactivated().observe(this, ignored -> openLogin());
    }

    /** 二次确认：顶部居中 warning 图标（error 色）、标题、正文；「确定注销」为 error 色 */
    private void showConfirmDialog() {
        if (confirmDialog != null && confirmDialog.isShowing()) {
            return;
        }
        KeyboardUtils.hide(binding.getRoot());
        confirmDialog = new MaterialAlertDialogBuilder(this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setIcon(com.heban.pestai.base.R.drawable.ic_warning)
                .setTitle(R.string.account_deactivate_confirm_title)
                .setMessage(R.string.account_deactivate_confirm_message)
                .setNegativeButton(com.heban.pestai.base.R.string.pest_cancel, null)
                .setPositiveButton(R.string.account_deactivate_confirm, (d, which) -> viewModel.confirmDeactivate())
                .create();
        confirmDialog.setOnShowListener(d -> {
            AlertDialog dialog = (AlertDialog) d;
            ImageView icon = dialog.findViewById(android.R.id.icon);
            if (icon != null) {
                icon.setImageTintList(ColorStateList.valueOf(
                        MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorError)));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(AppCompatResources.getColorStateList(this, R.color.account_text_error));
        });
        confirmDialog.show();
    }

    /** 清空任务栈打开登录页（等同 RouterPath.openClearTask，额外携带 account_deactivated） */
    private void openLogin() {
        Intent intent = RouterPath.intent(this, RouterPath.LOGIN)
                .putExtra(AccountExtras.EXTRA_ACCOUNT_DEACTIVATED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (confirmDialog != null) {
            confirmDialog.dismiss();
            confirmDialog = null;
        }
        super.onDestroy();
    }
}
