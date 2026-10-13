package com.heban.pestai.di;

import androidx.lifecycle.ViewModel;

import com.heban.pestai.base.di.ViewModelKey;
import com.heban.pestai.common.MainViewModel;

import java.util.Map;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;
import dagger.multibindings.Multibinds;

/**
 * ViewModel 多绑定的声明与壳工程自身的 ViewModel。各业务组件在自己的 XxxModule 中继续 @IntoMap 注册。
 */
@Module
public abstract class ViewModelModule {

    @Multibinds
    abstract Map<Class<? extends ViewModel>, ViewModel> viewModels();

    @Binds
    @IntoMap
    @ViewModelKey(MainViewModel.class)
    abstract ViewModel bindMainViewModel(MainViewModel viewModel);
}
