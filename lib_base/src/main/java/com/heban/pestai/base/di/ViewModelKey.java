package com.heban.pestai.base.di;

import androidx.lifecycle.ViewModel;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import dagger.MapKey;

/**
 * Dagger 多绑定的键：各业务组件在自己的 Dagger Module 中这样注册 ViewModel，
 * AppViewModelFactory 按类型创建实例。
 *
 * <pre>
 * &#64;Binds &#64;IntoMap &#64;ViewModelKey(HomeViewModel.class)
 * abstract ViewModel bindHomeViewModel(HomeViewModel viewModel);
 * </pre>
 */
@Documented
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@MapKey
public @interface ViewModelKey {
    Class<? extends ViewModel> value();
}
