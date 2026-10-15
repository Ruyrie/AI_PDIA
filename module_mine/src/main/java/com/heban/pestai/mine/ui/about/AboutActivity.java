package com.heban.pestai.mine.ui.about;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.mine.R;
import com.heban.pestai.mine.databinding.ActivityAboutBinding;
import com.heban.pestai.mine.databinding.DialogMineServerBinding;
import com.heban.pestai.mine.ui.MineUi;

/**
 * M03 关于（RouterPath.ABOUT）：版本信息、检查更新（v1.0 功能尚未开放）、用户协议、隐私政策。
 * 连续点击版本号 5 次打开「服务器地址」对话框；地址改变后清栈跳转登录页（登录态已由 ViewModel 清除）。
 */
public class AboutActivity extends BaseActivity {

    private AboutViewModel viewModel;
    @Nullable
    private AlertDialog serverDialog;

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
            case SHOW_SERVER_DIALOG:
                showServerDialog();
                break;
            default:
                break;
        }
    }

    private void openAgreement(String type) {
        MineUi.start(this, RouterPath.intent(this, RouterPath.AGREEMENT)
                .putExtra(RouterPath.EXTRA_AGREEMENT_TYPE, type));
    }

    /**
     * 「服务器地址」：预填当前地址，辅助文字为默认地址；「保存」「恢复默认」点击后先交给 ViewModel 判断，
     * 格式不正确时在输入框下方提示且不关闭对话框。没有使用自定义地址时「恢复默认」不可点。
     */
    private void showServerDialog() {
        if (serverDialog != null && serverDialog.isShowing()) {
            return;
        }
        DialogMineServerBinding view = DialogMineServerBinding.inflate(getLayoutInflater());
        String current = viewModel.getCurrentServer();
        view.mineServerInput.setText(current);
        view.mineServerInput.setSelection(current.length());
        view.mineServerLayout.setHelperText(getString(R.string.mine_server_default, viewModel.getDefaultServer()));
        view.mineServerInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                view.mineServerLayout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mine_server_title)
                .setView(view.getRoot())
                .setPositiveButton(R.string.mine_server_save, null)
                .setNegativeButton(com.heban.pestai.base.R.string.pest_cancel, null)
                .setNeutralButton(R.string.mine_server_reset, null)
                .create();
        view.mineServerInput.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSaveServer(dialog, view);
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            // 覆盖默认点击：格式不正确时不关闭对话框
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> onSaveServer(dialog, view));
            Button reset = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            reset.setEnabled(viewModel.isCustomServer());
            reset.setOnClickListener(v -> handleServerResult(dialog, viewModel.resetServer()));
            view.mineServerInput.requestFocus();
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        serverDialog = dialog;
        dialog.show();
    }

    private void onSaveServer(@NonNull AlertDialog dialog, @NonNull DialogMineServerBinding view) {
        CharSequence text = view.mineServerInput.getText();
        AboutViewModel.ServerResult result = viewModel.saveServer(text == null ? null : text.toString());
        if (result == AboutViewModel.ServerResult.INVALID) {
            view.mineServerLayout.setError(getString(R.string.mine_server_invalid));
            return;
        }
        handleServerResult(dialog, result);
    }

    private void handleServerResult(@NonNull AlertDialog dialog, @NonNull AboutViewModel.ServerResult result) {
        dialog.dismiss();
        if (result == AboutViewModel.ServerResult.CHANGED) {
            // 任务栈即将清空，Snackbar 来不及显示，改用 Toast
            Toast.makeText(getApplicationContext(), R.string.mine_server_changed, Toast.LENGTH_LONG).show();
            // NEW_TASK + CLEAR_TASK：登录页成为唯一页面，之后的请求都发往新地址
            RouterPath.openClearTask(this, RouterPath.LOGIN);
        }
    }

    @Override
    protected void onDestroy() {
        if (serverDialog != null) {
            serverDialog.dismiss();
            serverDialog = null;
        }
        super.onDestroy();
    }
}
