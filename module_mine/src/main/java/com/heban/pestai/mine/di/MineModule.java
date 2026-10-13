package com.heban.pestai.mine.di;

import androidx.lifecycle.ViewModel;

import com.heban.pestai.base.di.ViewModelKey;
import com.heban.pestai.mine.data.MineApi;
import com.heban.pestai.mine.ui.MineViewModel;
import com.heban.pestai.mine.ui.about.AboutViewModel;
import com.heban.pestai.mine.ui.agreement.AgreementViewModel;
import com.heban.pestai.mine.ui.settings.SettingsViewModel;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import retrofit2.Retrofit;

/**
 * module_mine 的 Dagger Module（M01–M03 与协议页（模块09）），由壳工程 AppComponent 组合。
 * <ul>
 *     <li>MineApi 由 Retrofit 创建；MineRepository、CacheRepository、SettingsRepository、AgreementRepository、
 *     UpdateRepository（预留）、AppVersion 使用 {@code @Singleton} + {@code @Inject} 构造方法。</li>
 *     <li>ViewModel 依赖的 IUserService 由 module_account 的 AccountModule 绑定（AppComponent 已对外提供）。</li>
 * </ul>
 */
@Module
public abstract class MineModule {

    @Provides
    @Singleton
    static MineApi provideMineApi(Retrofit retrofit) {
        return retrofit.create(MineApi.class);
    }

    @Binds
    @IntoMap
    @ViewModelKey(MineViewModel.class)
    abstract ViewModel bindMineViewModel(MineViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(SettingsViewModel.class)
    abstract ViewModel bindSettingsViewModel(SettingsViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(AboutViewModel.class)
    abstract ViewModel bindAboutViewModel(AboutViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(AgreementViewModel.class)
    abstract ViewModel bindAgreementViewModel(AgreementViewModel viewModel);
}
