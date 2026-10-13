package com.heban.pestai.account.ui.profile;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.heban.pestai.account.AccountExtras;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityProfileSetupBinding;
import com.heban.pestai.account.ui.login.LoginEvent;
import com.heban.pestai.account.util.ProfileRules;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A06 完善资料（注册第 2 步）。
 * <ul>
 *     <li>头像：系统照片选择器（PickVisualMedia，无需存储权限）→ 本地预览 → 压缩上传；</li>
 *     <li>「开始使用」保存后、「跳过」或返回键都先以引导模式打开 A12 保存账号恢复码（Intent 布尔参数 from_register）
 *     并结束本页；A12 再以清空任务栈方式进入首页（账号已创建并登录，不再回到注册 / 登录页）。</li>
 * </ul>
 */
public class ProfileSetupActivity extends BaseActivity {

    private ActivityProfileSetupBinding binding;
    private ProfileSetupViewModel viewModel;
    private MaterialButton[] identityButtons;
    private final Map<String, Chip> cropChips = new LinkedHashMap<>();

    private final ActivityResultLauncher<PickVisualMediaRequest> pickAvatar =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    viewModel.uploadAvatar(uri.toString());
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_profile_setup);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(ProfileSetupViewModel.class);
        viewModel.init();
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        // 头像：primaryContainer 底，onPrimaryContainer 色 40sp Medium 首字
        binding.profileAvatar.getTextView().setTextColor(
                DimenUtils.themeColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer));
        binding.profileAvatar.getTextView().setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        RxClick.throttle(binding.profileAvatarContainer, v -> pickAvatar());

        setupIdentity();
        setupCrops();

        // 返回键与顶栏返回：账号已创建，等同「跳过」（进入 A12 引导模式）
        binding.profileToolbar.setNavigationOnClickListener(v -> viewModel.skip());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                viewModel.skip();
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
    }

    private void pickAvatar() {
        if (Boolean.TRUE.equals(viewModel.getAvatarUploading().getValue())) {
            return;
        }
        pickAvatar.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
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
}
