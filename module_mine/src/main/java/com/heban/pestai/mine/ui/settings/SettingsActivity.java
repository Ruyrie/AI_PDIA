package com.heban.pestai.mine.ui.settings;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.mine.R;
import com.heban.pestai.mine.databinding.ActivitySettingsBinding;
import com.heban.pestai.mine.ui.MineUi;

/**
 * M02 设置（RouterPath.SETTINGS）。
 * 「手机号」行 → A10 更换手机号（RouterPath.CHANGE_PHONE），「账号恢复码」→ A12（RouterPath.RECOVERY_CODE），
 * 「注销账号」→ A11（RouterPath.DEACTIVATE）；观察 IUserService.userChanges()，从 A10 返回后手机号自动刷新；
 * onResume 时刷新恢复码状态，从 A12 生成后返回即显示「已生成」。
 */
public class SettingsActivity extends BaseActivity {

    private ActivitySettingsBinding binding;
    private SettingsViewModel viewModel;
    @Nullable
    private AlertDialog logoutDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_settings);
        viewModel = obtainViewModel(SettingsViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        InsetsHelper.applySystemBars(binding.settingsRoot);

        binding.settingsToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        // 整行点击切换开关；点在开关上时由开关自身消费触摸事件，整行收不到点击
        binding.settingsRowAlbum.setOnClickListener(v -> binding.settingsSwitchAlbum.toggle());

        // 手机号行：登录后可点击进入 A10；游客不可点击（没有水波纹）
        viewModel.getGuest().observe(this, guest -> {
            if (Boolean.TRUE.equals(guest)) {
                binding.settingsRowPhone.setOnClickListener(null);
                binding.settingsRowPhone.setClickable(false);
            } else {
                RxClick.throttle(binding.settingsRowPhone, v -> viewModel.onPhoneClick());
            }
        });

        observeToast(viewModel);
        viewModel.getEvent().observe(this, this::onEvent);
        viewModel.getLoggingOut().observe(this, loggingOut -> updateLogoutButton());
        ServiceHub.get(IUserService.class).userChanges().observe(this, viewModel::onUserChanged);
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.refreshRecoveryCode();
    }

    private void onEvent(@Nullable SettingsEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type) {
            case GO_CHANGE_PHONE:
                MineUi.open(this, RouterPath.CHANGE_PHONE);
                break;
            case GO_DEACTIVATE:
                MineUi.open(this, RouterPath.DEACTIVATE);
                break;
            case GO_RECOVERY_CODE:
                MineUi.open(this, RouterPath.RECOVERY_CODE);
                break;
            case GO_CHANGE_PWD:
                String phone = viewModel.getFullPhone();
                if (phone != null) {
                    MineUi.start(this, RouterPath.intent(this, RouterPath.FORGOT_PWD)
                            .putExtra(RouterPath.EXTRA_PHONE, phone)
                            .putExtra(RouterPath.EXTRA_FROM_SETTINGS, true));
                }
                break;
            case GO_ABOUT:
                MineUi.open(this, RouterPath.ABOUT);
                break;
            case GO_SWITCH_ACCOUNT:
                // 普通启动，不清栈：在 A03 返回仍回到设置页，原账号保持登录
                MineUi.open(this, RouterPath.LOGIN);
                break;
            case SHOW_LOGOUT_DIALOG:
                showLogoutDialog();
                break;
            case CACHE_CLEARED:
                if (event.message != null) {
                    MineUi.showSnackbar(this, event.message, com.heban.pestai.base.R.drawable.ic_check_circle);
                }
                break;
            case LOGGED_OUT:
                dismissLogoutDialog();
                // NEW_TASK + CLEAR_TASK：清空整个任务栈，A03 成为唯一页面，返回键直接退出应用
                RouterPath.openClearTask(this, RouterPath.LOGIN);
                break;
            default:
                break;
        }
    }

    /** 退出确认：顶部居中 logout 图标（primary）、标题、正文；「退出登录」error 色，请求期间禁用且不关闭对话框 */
    private void showLogoutDialog() {
        if (logoutDialog != null && logoutDialog.isShowing()) {
            return;
        }
        logoutDialog = new MaterialAlertDialogBuilder(this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setIcon(com.heban.pestai.base.R.drawable.ic_logout)
                .setTitle(R.string.mine_logout_title)
                .setMessage(R.string.mine_logout_message)
                .setNegativeButton(com.heban.pestai.base.R.string.pest_cancel, null)
                .setPositiveButton(R.string.mine_settings_logout, null)
                .create();
        logoutDialog.setOnShowListener(d -> {
            AlertDialog dialog = (AlertDialog) d;
            ImageView icon = dialog.findViewById(android.R.id.icon);
            if (icon != null) {
                icon.setImageTintList(ColorStateList.valueOf(
                        MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorPrimary)));
            }
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setTextColor(AppCompatResources.getColorStateList(this, R.color.mine_text_error));
            // 覆盖默认点击：点击后不自动关闭，等待退出完成后清栈跳转
            positive.setOnClickListener(v -> viewModel.confirmLogout());
            updateLogoutButton();
        });
        logoutDialog.show();
    }

    private void updateLogoutButton() {
        if (logoutDialog == null || !logoutDialog.isShowing()) {
            return;
        }
        boolean busy = Boolean.TRUE.equals(viewModel.getLoggingOut().getValue());
        Button positive = logoutDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = logoutDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (positive != null) {
            positive.setEnabled(!busy);
        }
        if (negative != null) {
            negative.setEnabled(!busy);
        }
        logoutDialog.setCancelable(!busy);
    }

    private void dismissLogoutDialog() {
        if (logoutDialog != null) {
            logoutDialog.dismiss();
            logoutDialog = null;
        }
    }

    @Override
    protected void onDestroy() {
        dismissLogoutDialog();
        super.onDestroy();
    }
}
