package com.heban.pestai.home.di;

import androidx.lifecycle.ViewModel;

import com.heban.pestai.base.di.ViewModelKey;
import com.heban.pestai.home.HomeViewModel;
import com.heban.pestai.home.data.HomeApi;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import retrofit2.Retrofit;

/**
 * module_home 的 Dagger Module（H01（模块04）），由壳工程 AppComponent 组合。
 * HomeRepository 使用 {@code @Singleton} + {@code @Inject} 构造方法，无需在这里声明。
 */
@Module
public abstract class HomeModule {

    @Provides
    @Singleton
    static HomeApi provideHomeApi(Retrofit retrofit) {
        return retrofit.create(HomeApi.class);
    }

    @Binds
    @IntoMap
    @ViewModelKey(HomeViewModel.class)
    abstract ViewModel bindHomeViewModel(HomeViewModel viewModel);
}
