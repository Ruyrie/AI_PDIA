package com.heban.pestai.account.ui.splash;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.databinding.DataBindingUtil;

import com.heban.pestai.account.AccountExtras;
import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivitySplashBinding;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.ReloginExempt;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.common.router.RouterPath;

/**
 * A01 闪屏，整个 App 的启动入口（MAIN / LAUNCHER）。
 * <ol>
 *     <li>installSplashScreen() 接管系统启动画面（启动主题 Theme.Account.Starting，背景同为 surface 色）；</li>
 *     <li>系统画面在 200ms 内淡出，下方 A01 的 Logo 随之显露；</li>
 *     <li>SplashViewModel 计时 1.5 秒并校验登录态，观察到跳转目标后启动目标页并 finish，闪屏不留在返回栈。</li>
 * </ol>
 * 实现 ReloginExempt：闪屏期间不响应全局「需要重新登录」事件，过期提示由登录页根据 session_expired 显示。
 */
public class SplashActivity extends BaseActivity implements ReloginExempt {

    private static final long EXIT_FADE_MS = 200;

    private SplashViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        ActivitySplashBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_splash);
        InsetsHelper.applySystemBars(binding.getRoot());

        viewModel = obtainViewModel(SplashViewModel.class);
        viewModel.setVersionName(versionName());
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);

        splashScreen.setOnExitAnimationListener(provider -> provider.getView().animate()
                .alpha(0f)
                .setDuration(EXIT_FADE_MS)
                .withEndAction(provider::remove)
                .start());

        viewModel.getDestination().observe(this, this::navigate);
        viewModel.start();
    }

    private void navigate(@NonNull SplashViewModel.Destination destination) {
        Intent intent;
        switch (destination) {
            case ONBOARDING:
                intent = RouterPath.intent(this, RouterPath.ONBOARDING);
                break;
            case MAIN:
                intent = RouterPath.intent(this, RouterPath.MAIN);
                break;
            case LOGIN:
            default:
                intent = RouterPath.intent(this, RouterPath.LOGIN);
                if (viewModel.isSessionExpired()) {
                    intent.putExtra(AccountExtras.EXTRA_SESSION_EXPIRED, true);
                }
                break;
        }
        ActivityOptions options = ActivityOptions.makeCustomAnimation(this,
                com.heban.pestai.base.R.anim.pest_fade_in, com.heban.pestai.base.R.anim.pest_fade_out);
        startActivity(intent, options.toBundle());
        finish();
    }

    /** 应用 versionName（库组件的 BuildConfig 不含应用版本号） */
    @Nullable
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
