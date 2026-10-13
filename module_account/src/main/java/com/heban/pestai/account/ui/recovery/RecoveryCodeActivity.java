package com.heban.pestai.account.ui.recovery;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.PersistableBundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.account.AccountExtras;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityRecoveryCodeBinding;
import com.heban.pestai.account.ui.common.FormFields;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.common.router.RouterPath;

/**
 * A12 账号恢复码（RouterPath.RECOVERY_CODE）。入口：M02 设置页「账号恢复码」、M01 未生成提醒卡片「去生成」；
 * 新用户在 A06 完善资料之后以引导模式打开（Intent 布尔参数 {@link AccountExtras#EXTRA_FROM_REGISTER}）。
 * <ul>
 *     <li>重新生成前确认「重新生成后，旧恢复码将立即失效」；</li>
 *     <li>「复制」写入剪贴板（标记为敏感内容，系统剪贴板预览不显示明文）；</li>
 *     <li>结果显示后未勾选「我已抄写或截图保存」就返回时提醒「恢复码只显示这一次，确定已保存？」；</li>
 *     <li>引导模式：标题「保存账号恢复码」、不显示顶栏返回箭头；「稍后再说」/「完成」/ 系统返回键都以清空任务栈方式进入首页。</li>
 * </ul>
 * 不设置 FLAG_SECURE：说明中允许用户截图保存。
 */
public class RecoveryCodeActivity extends BaseActivity {

    /** ClipDescription.EXTRA_IS_SENSITIVE（API 33）；低版本系统忽略该标记 */
    private static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";

    private ActivityRecoveryCodeBinding binding;
    private RecoveryCodeViewModel viewModel;
    @Nullable
    private AlertDialog dialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_recovery_code);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(RecoveryCodeViewModel.class);
        boolean fromRegister = getIntent().getBooleanExtra(AccountExtras.EXTRA_FROM_REGISTER, false);
        viewModel.init(fromRegister);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        if (fromRegister) {
            // 引导模式是注册流程的最后一步：没有可返回的上一页，「稍后再说」与系统返回键都进入首页
            binding.recoveryToolbar.setTitle(R.string.account_recovery_guide_title);
            binding.recoveryToolbar.setNavigationIcon(null);
        }

        FormFields.setupCodeAction(binding.recoverySmsLayout, v -> viewModel.sendCode());
        binding.recoverySavedText.setOnClickListener(v -> binding.recoverySaved.toggle());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                viewModel.back();
            }
        });
        binding.recoveryToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        viewModel.getFocusCode().observe(this, ignored -> {
            binding.recoverySmsInput.requestFocus();
            KeyboardUtils.show(binding.recoverySmsInput);
        });
        final boolean[] hadResult = {Boolean.TRUE.equals(viewModel.getShowResult().getValue())};
        viewModel.getShowResult().observe(this, show -> {
            boolean isShown = Boolean.TRUE.equals(show);
            if (isShown && !hadResult[0]) {
                KeyboardUtils.hide(binding.getRoot());
                binding.recoveryScroll.post(() ->
                        binding.recoveryScroll.smoothScrollTo(0, binding.recoveryResult.getTop()));
            }
            hadResult[0] = isShown;
        });
        viewModel.getShowRegenerateConfirm().observe(this, ignored -> showRegenerateConfirm());
        viewModel.getShowLeaveConfirm().observe(this, ignored -> showLeaveConfirm());
        viewModel.getCopyCode().observe(this, this::copyToClipboard);
        viewModel.getFinish().observe(this, ignored -> finish());
        viewModel.getGoMain().observe(this, ignored -> {
            RouterPath.openClearTask(this, RouterPath.MAIN);
            finish();
        });
    }

    private void showRegenerateConfirm() {
        if (dialog != null && dialog.isShowing()) {
            return;
        }
        KeyboardUtils.hide(binding.getRoot());
        dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.account_recovery_regenerate_confirm_title)
                .setMessage(R.string.account_recovery_regenerate_confirm_message)
                .setNegativeButton(com.heban.pestai.base.R.string.pest_cancel, null)
                .setPositiveButton(R.string.account_recovery_regenerate_confirm, (d, which) -> viewModel.confirmRegenerate())
                .show();
    }

    private void showLeaveConfirm() {
        if (dialog != null && dialog.isShowing()) {
            return;
        }
        dialog = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.account_recovery_leave_message)
                .setNegativeButton(R.string.account_recovery_leave_stay, null)
                .setPositiveButton(R.string.account_recovery_leave_confirm, (d, which) -> viewModel.leave())
                .show();
    }

    private void copyToClipboard(@NonNull String code) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        ClipData clip = ClipData.newPlainText(getString(R.string.account_recovery_clip_label), code);
        ClipDescription description = clip.getDescription();
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_IS_SENSITIVE, true);
        description.setExtras(extras);
        clipboard.setPrimaryClip(clip);
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        super.onDestroy();
    }
}
