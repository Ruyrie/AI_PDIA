package com.heban.pestai.account.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.tabs.TabLayout;
import com.heban.pestai.account.AccountExtras;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityLoginBinding;
import com.heban.pestai.account.ui.common.FormFields;
import com.heban.pestai.account.ui.common.LinkText;
import com.heban.pestai.account.ui.common.TabIndicatorDrawable;
import com.heban.pestai.account.util.PhoneRules;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.ReloginExempt;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;

/**
 * A03 密码登录 / A04 验证码登录。
 * <ul>
 *     <li>Tab 切换登录方式，手机号与协议勾选保留；</li>
 *     <li>登录成功：老用户以清空任务栈方式进入首页，验证码自动注册的新用户进入 A06；</li>
 *     <li>Intent 布尔参数 session_expired 为 true 时（闪屏或壳工程因令牌失效跳转而来）显示「登录已过期，请重新登录」；</li>
 *     <li>Intent 布尔参数 account_deactivated 为 true 时（A11 注销成功后清栈跳转而来）显示「账号已注销」。</li>
 * </ul>
 * 实现 ReloginExempt：位于前台时不响应全局「需要重新登录」事件，避免重复打开登录页。
 */
public class LoginActivity extends BaseActivity implements ReloginExempt {

    private ActivityLoginBinding binding;
    private LoginViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_login);
        InsetsHelper.apply(binding.getRoot(), true, true, true);
        viewModel = obtainViewModel(LoginViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        observeToast(viewModel);

        FormFields.setupPhone(binding.loginPhoneLayout);
        FormFields.setupCodeAction(binding.loginCodeLayout, v -> viewModel.sendCode());
        LinkText.bindAgreement(binding.loginAgreementText);
        LinkText.bind(binding.loginNoCodeDesc, getString(R.string.account_no_code_desc),
                getString(R.string.account_tab_password), v -> viewModel.switchMode(LoginViewModel.Mode.PASSWORD));
        setupTabs();

        RxClick.throttle(binding.loginForgot, v -> openForgotPassword());
        RxClick.throttle(binding.loginRegister, v -> RouterPath.open(this, RouterPath.REGISTER));

        viewModel.getResult().observe(this, this::onResult);
        viewModel.getAgreementShake().observe(this, ignored -> FormFields.shake(binding.loginAgreementRow));
        viewModel.getFocusCode().observe(this, ignored -> {
            EditText input = binding.loginCodeInput;
            input.requestFocus();
            KeyboardUtils.show(input);
        });
        viewModel.getSelectCode().observe(this, ignored -> {
            EditText input = binding.loginCodeInput;
            input.requestFocus();
            input.selectAll();
        });

        if (savedInstanceState == null) {
            Intent intent = getIntent();
            if (intent.getBooleanExtra(AccountExtras.EXTRA_ACCOUNT_DEACTIVATED, false)) {
                showSnackbar(getString(R.string.account_deactivated));
            } else if (intent.getBooleanExtra(AccountExtras.EXTRA_SESSION_EXPIRED, false)) {
                showSnackbar(getString(R.string.account_session_expired));
            }
        }
    }

    private void setupTabs() {
        TabLayout tabs = binding.loginTabs;
        tabs.setSelectedTabIndicator(new TabIndicatorDrawable(DimenUtils.dp(this, 56), DimenUtils.dp(this, 3),
                DimenUtils.themeColor(this, androidx.appcompat.R.attr.colorPrimary)));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewModel.switchMode(tab.getPosition() == 1 ? LoginViewModel.Mode.SMS : LoginViewModel.Mode.PASSWORD);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        viewModel.getMode().observe(this, mode -> {
            int index = mode == LoginViewModel.Mode.SMS ? 1 : 0;
            if (tabs.getSelectedTabPosition() != index) {
                TabLayout.Tab tab = tabs.getTabAt(index);
                if (tab != null) {
                    tab.select();
                }
            }
        });
    }

    private void onResult(@NonNull LoginEvent event) {
        if (event == LoginEvent.GO_PROFILE_SETUP) {
            startActivity(RouterPath.intent(this, RouterPath.PROFILE_SETUP));
            finish();
        } else {
            RouterPath.openClearTask(this, RouterPath.MAIN);
            finish();
        }
    }

    /** 「忘记密码？」进入 A07；已输入合法手机号时带入（仍可修改） */
    private void openForgotPassword() {
        Intent intent = RouterPath.intent(this, RouterPath.FORGOT_PWD);
        String phone = viewModel.getPhone().getValue();
        if (PhoneRules.isValid(phone)) {
            intent.putExtra(RouterPath.EXTRA_PHONE, PhoneRules.normalize(phone));
        }
        startActivity(intent);
    }
}
