package com.heban.pestai.base;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

/**
 * 由壳工程的 Application（PestApp）实现。业务组件不依赖壳工程，
 * 通过该接口取得 Dagger 提供的 ViewModel 工厂与组件依赖。
 *
 * <p>业务组件需要 ViewModel 以外的对象（如 Service 中的 Repository）时，在本组件定义
 * {@code XxxDeps} 接口声明所需对象，AppComponent 继承该接口，然后调用
 * {@code AppHost.deps(context, XxxDeps.class)} 获取。</p>
 */
public interface AppHost {

    ViewModelProvider.Factory getViewModelFactory();

    /** Dagger AppComponent 实例 */
    Object getComponent();

    static AppHost from(@NonNull Context context) {
        return (AppHost) context.getApplicationContext();
    }

    static <T> T deps(@NonNull Context context, @NonNull Class<T> depsClass) {
        return depsClass.cast(from(context).getComponent());
    }
}
