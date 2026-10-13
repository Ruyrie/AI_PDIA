package com.heban.pestai.account.di;

import androidx.lifecycle.ViewModel;

import com.heban.pestai.account.UserServiceImpl;
import com.heban.pestai.account.data.AuthApi;
import com.heban.pestai.account.ui.deactivate.DeactivateViewModel;
import com.heban.pestai.account.ui.forgot.ForgotPwdViewModel;
import com.heban.pestai.account.ui.login.LoginViewModel;
import com.heban.pestai.account.ui.onboarding.OnboardingViewModel;
import com.heban.pestai.account.ui.phone.ChangePhoneViewModel;
import com.heban.pestai.account.ui.profile.ProfileSetupViewModel;
import com.heban.pestai.account.ui.recover.RecoverAccountViewModel;
import com.heban.pestai.account.ui.recovery.RecoveryCodeViewModel;
import com.heban.pestai.account.ui.register.RegisterViewModel;
import com.heban.pestai.account.ui.reset.ResetPwdViewModel;
import com.heban.pestai.account.ui.splash.SplashViewModel;
import com.heban.pestai.base.di.ViewModelKey;
import com.heban.pestai.common.service.IUserService;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import retrofit2.Retrofit;

/**
 * module_account 的 Dagger Module（A01–A12：模块01–03 与新增的找回账号、更换手机号、注销账号、账号恢复码），由壳工程 AppComponent 组合。
 * AccountRepository 使用 {@code @Singleton} + {@code @Inject} 构造方法，无需在这里声明。
 */
@Module
public abstract class AccountModule {

    /** IUserService 由本组件实现，PestApp 启动时注册到 ServiceHub */
    @Binds
    abstract IUserService bindUserService(UserServiceImpl impl);

    @Provides
    @Singleton
    static AuthApi provideAuthApi(Retrofit retrofit) {
        return retrofit.create(AuthApi.class);
    }

    @Binds
    @IntoMap
    @ViewModelKey(SplashViewModel.class)
    abstract ViewModel bindSplashViewModel(SplashViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(OnboardingViewModel.class)
    abstract ViewModel bindOnboardingViewModel(OnboardingViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(LoginViewModel.class)
    abstract ViewModel bindLoginViewModel(LoginViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(RegisterViewModel.class)
    abstract ViewModel bindRegisterViewModel(RegisterViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(ProfileSetupViewModel.class)
    abstract ViewModel bindProfileSetupViewModel(ProfileSetupViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(ForgotPwdViewModel.class)
    abstract ViewModel bindForgotPwdViewModel(ForgotPwdViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(ResetPwdViewModel.class)
    abstract ViewModel bindResetPwdViewModel(ResetPwdViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(RecoverAccountViewModel.class)
    abstract ViewModel bindRecoverAccountViewModel(RecoverAccountViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(ChangePhoneViewModel.class)
    abstract ViewModel bindChangePhoneViewModel(ChangePhoneViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(DeactivateViewModel.class)
    abstract ViewModel bindDeactivateViewModel(DeactivateViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(RecoveryCodeViewModel.class)
    abstract ViewModel bindRecoveryCodeViewModel(RecoveryCodeViewModel viewModel);
}
