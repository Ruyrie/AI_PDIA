package com.heban.pestai.account.ui.splash;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.vo.UserVO;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;

/**
 * A01 闪屏：1.5 秒计时与 GET user/me 校验并行（Single.zip），两者都完成后按决策表给出跳转目标。
 *
 * <table>
 *     <tr><th>onboarding_done</th><th>token</th><th>校验结果</th><th>目标</th></tr>
 *     <tr><td>false</td><td>任意</td><td>不校验</td><td>ONBOARDING</td></tr>
 *     <tr><td>true</td><td>空</td><td>不校验</td><td>LOGIN</td></tr>
 *     <tr><td>true</td><td>非空</td><td>有效 / 断网 / 超时</td><td>MAIN</td></tr>
 *     <tr><td>true</td><td>非空</td><td>40101</td><td>LOGIN（session_expired）</td></tr>
 * </table>
 */
public class SplashViewModel extends BaseViewModel {

    /** 闪屏最短停留 */
    public static final long SPLASH_MILLIS = 1500;

    public enum Destination {
        ONBOARDING, LOGIN, MAIN
    }

    private final AccountRepository repository;
    private final SingleLiveEvent<Destination> destination = new SingleLiveEvent<>();
    private final MutableLiveData<String> versionText = new MutableLiveData<>(AccountTexts.version(null));

    @Nullable
    private UserVO cachedUser;
    private boolean sessionExpired;
    private boolean started;

    @Inject
    public SplashViewModel(AccountRepository repository) {
        this.repository = repository;
    }

    /** 版本号来自应用的 versionName（库组件自身 BuildConfig 不含应用版本号，由页面从 PackageManager 读取） */
    public void setVersionName(@Nullable String versionName) {
        versionText.setValue(AccountTexts.version(versionName));
    }

    /** 页面创建时调用；只执行一次，旋转屏幕不会重新计时 */
    public void start() {
        if (started) {
            return;
        }
        started = true;

        // 本地状态快照：只读一次
        final boolean onboardingDone = repository.isOnboardingDone();
        final boolean hasToken = repository.hasToken();
        cachedUser = repository.cachedUser();

        Single<Long> timer = Single.timer(SPLASH_MILLIS, TimeUnit.MILLISECONDS);
        Single<Boolean> check = onboardingDone && hasToken
                ? repository.verifyToken()
                : Single.just(Boolean.FALSE);
        add(Single.zip(timer, check, (ignored, valid) -> valid)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(valid -> decide(onboardingDone, hasToken, valid),
                        e -> decide(onboardingDone, hasToken, hasToken)));
    }

    private void decide(boolean onboardingDone, boolean hasToken, boolean tokenValid) {
        if (!onboardingDone) {
            destination.setValue(Destination.ONBOARDING);
        } else if (hasToken && tokenValid) {
            destination.setValue(Destination.MAIN);
        } else {
            sessionExpired = hasToken;
            destination.setValue(Destination.LOGIN);
        }
    }

    public LiveData<Destination> getDestination() {
        return destination;
    }

    public LiveData<String> getVersionText() {
        return versionText;
    }

    /** 目标为 LOGIN 时读取：本地有令牌但服务器判定失效，登录页显示「登录已过期，请重新登录」 */
    public boolean isSessionExpired() {
        return sessionExpired;
    }

    @Nullable
    UserVO getCachedUser() {
        return cachedUser;
    }
}
