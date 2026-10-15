package com.heban.pestai.account.ui.profile;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.account.AccountExtras;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityProfileSetupBinding;
import com.heban.pestai.account.ui.login.LoginEvent;
import com.heban.pestai.account.util.AccountFileProvider;
import com.heban.pestai.account.util.ProfileRules;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * A06 完善资料（注册第 2 步）/ 编辑资料（M01 我的进入，Intent 参数 RouterPath.EXTRA_PROFILE_MODE = PROFILE_MODE_EDIT）。
 * <ul>
 *     <li>头像：点击弹出「拍照 / 从相册选择 / 取消」（{@link AvatarSourceSheet}），拍照与选图由 {@link AvatarPicker} 处理
 *     → 本地预览 → 压缩上传；上传中不能再次选择，也不能保存；离开本页后删除拍照缓存。</li>
 *     <li>完善资料：「开始使用」保存后、「跳过」或返回键都先以引导模式打开 A12 保存账号恢复码（Intent 布尔参数 from_register）
 *     并结束本页；A12 再以清空任务栈方式进入首页（账号已创建并登录，不再回到注册 / 登录页）。</li>
 *     <li>编辑资料：顶栏「编辑资料」，隐藏步骤说明与「跳过」，按钮「保存」；保存成功提示「已保存」并返回（不进入 A12），
 *     首页与我的页随 UserSession.userChanges() 刷新；有未保存的修改时返回先确认「放弃本次修改？」。</li>
 * </ul>
 */
public class ProfileSetupActivity extends BaseActivity {

    private ActivityProfileSetupBinding binding;
    private ProfileSetupViewModel viewModel;
    private AvatarPicker avatarPicker;
    private MaterialButton[] identityButtons;
    private final Map<String, Chip> cropChips = new LinkedHashMap<>();
    @Nullable
    private AlertDialog dialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_profile_setup);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(ProfileSetupViewModel.class);
        viewModel.init(RouterPath.PROFILE_MODE_EDIT.equals(getIntent().getStringExtra(RouterPath.EXTRA_PROFILE_MODE)));
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        if (viewModel.isEditMode()) {
            binding.profileToolbar.setTitle(R.string.account_profile_edit_title);
        }

        // 头像：primaryContainer 底，onPrimaryContainer 色 40sp Medium 首字
        binding.profileAvatar.getTextView().setTextColor(
                DimenUtils.themeColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer));
        binding.profileAvatar.getTextView().setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        setupAvatarPicker();

        setupIdentity();
        setupCrops();

        // 返回键与顶栏返回：完善资料等同「跳过」（进入 A12 引导模式）；编辑资料有修改时先确认
        binding.profileToolbar.setNavigationOnClickListener(v -> viewModel.onBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                viewModel.onBack();
            }
        });

        viewModel.getDone().observe(this, event -> {
            if (event == LoginEvent.GO_RECOVERY_GUIDE) {
                // A12 引导模式结束时再以清空任务栈方式进入首页；本页结束，不会再回到完善资料
                startActivity(RouterPath.intent(this, RouterPath.RECOVERY_CODE)
                        .putExtra(AccountExtras.EXTRA_FROM_REGISTER, true));
                finish();
            }
        });
        viewModel.getEditEvent().observe(this, this::onEditEvent);
    }

    private void setupAvatarPicker() {
        // 必须在 STARTED 之前注册
        avatarPicker = AvatarPicker.register(this, new AvatarPicker.Callback() {
            @Override
            public void onAvatarPicked(@NonNull String uri) {
                viewModel.uploadAvatar(uri);
            }

            @Override
            public void onPickFailed(int message) {
                showSnackbar(getString(message));
            }
        });
        getSupportFragmentManager().setFragmentResultListener(AvatarSourceSheet.REQUEST_KEY, this, (key, result) -> {
            if (AvatarSourceSheet.sourceOf(result) == AvatarSourceSheet.SOURCE_CAMERA) {
                avatarPicker.takePhoto();
            } else {
                avatarPicker.pickFromAlbum();
            }
        });
        RxClick.throttle(binding.profileAvatarContainer, v -> showAvatarSource());
    }

    private void showAvatarSource() {
        if (Boolean.TRUE.equals(viewModel.getAvatarUploading().getValue())) {
            return;
        }
        KeyboardUtils.hide(binding.getRoot());
        AvatarSourceSheet.show(getSupportFragmentManager());
    }

    private void onEditEvent(@Nullable ProfileEditEvent event) {
        if (event == null) {
            return;
        }
        switch (event) {
            case SAVED:
                // 页面随即关闭，用 Toast 而不是 Snackbar（与 M03 修改服务器地址后的提示一致）
                Toast.makeText(getApplicationContext(), R.string.account_profile_saved, Toast.LENGTH_SHORT).show();
                finish();
                break;
            case CONFIRM_DISCARD:
                showDiscardConfirm();
                break;
            case CLOSE:
                finish();
                break;
            default:
                break;
        }
    }

    private void showDiscardConfirm() {
        if (dialog != null && dialog.isShowing()) {
            return;
        }
        KeyboardUtils.hide(binding.getRoot());
        dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.account_profile_discard_title)
                .setNegativeButton(R.string.account_profile_keep_editing, null)
                .setPositiveButton(R.string.account_profile_discard, (d, which) -> viewModel.discard())
                .show();
    }

    private void setupIdentity() {
        identityButtons = new MaterialButton[]{
                binding.profileIdentityFarmer, binding.profileIdentityGardener, binding.profileIdentityTechnician};
        for (int i = 0; i < identityButtons.length; i++) {
            identityButtons[i].setText(ProfileRules.IDENTITIES.get(i));
        }
        binding.profileIdentityGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            for (int i = 0; i < identityButtons.length; i++) {
                if (identityButtons[i].getId() == checkedId) {
                    // 每个按钮使用独立的 Drawable 实例，避免共享 bounds / tint
                    Drawable check = isChecked
                            ? ContextCompat.getDrawable(this, com.heban.pestai.base.R.drawable.ic_check)
                            : null;
                    identityButtons[i].setIcon(check);
                    if (isChecked) {
                        viewModel.selectIdentity(ProfileRules.IDENTITIES.get(i));
                    }
                }
            }
        });
        viewModel.getIdentity().observe(this, value -> {
            int index = ProfileRules.IDENTITIES.indexOf(value);
            if (index >= 0 && binding.profileIdentityGroup.getCheckedButtonId() != identityButtons[index].getId()) {
                binding.profileIdentityGroup.check(identityButtons[index].getId());
            }
        });
    }

    private void setupCrops() {
        for (String crop : ProfileRules.CROPS) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_account_crop_chip,
                    binding.profileCropGroup, false);
            chip.setText(crop);
            chip.setOnClickListener(v -> viewModel.toggleCrop(crop));
            binding.profileCropGroup.addView(chip);
            cropChips.put(crop, chip);
        }
        viewModel.getSelectedCrops().observe(this, this::renderCrops);
    }

    private void renderCrops(@Nullable Set<String> selected) {
        for (Map.Entry<String, Chip> entry : cropChips.entrySet()) {
            boolean checked = selected != null && selected.contains(entry.getKey());
            if (entry.getValue().isChecked() != checked) {
                entry.getValue().setChecked(checked);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        if (isFinishing()) {
            // 离开本页后不再需要拍照原图（已上传的头像在服务器上，未保存的修改已放弃）；文件操作放到 io 线程
            Context app = getApplicationContext();
            Schedulers.io().scheduleDirect(() -> AccountFileProvider.clearAll(app));
        }
        super.onDestroy();
    }
}
