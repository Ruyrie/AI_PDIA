package com.heban.pestai.di;

import android.app.Application;

import com.heban.pestai.account.di.AccountDeps;
import com.heban.pestai.account.di.AccountModule;
import com.heban.pestai.ai.di.AiDeps;
import com.heban.pestai.ai.di.AiModule;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.session.SessionEvents;
import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.home.di.HomeDeps;
import com.heban.pestai.home.di.HomeModule;
import com.heban.pestai.mine.di.MineDeps;
import com.heban.pestai.mine.di.MineModule;
import com.heban.pestai.network.di.NetworkModule;
import com.heban.pestai.recognize.di.RecognizeDeps;
import com.heban.pestai.recognize.di.RecognizeModule;
import com.heban.pestai.record.di.RecordDeps;
import com.heban.pestai.record.di.RecordModule;

import javax.inject.Singleton;

import dagger.BindsInstance;
import dagger.Component;

/**
 * 全局单例组件：组合 NetworkModule 与各业务组件的 Module，对外提供 AppViewModelFactory 与组件间服务。
 * 继承各业务组件的 XxxDeps 接口，业务组件通过 AppHost.deps(context, XxxDeps.class) 取得所需对象。
 */
@Singleton
@Component(modules = {
        AppModule.class,
        NetworkModule.class,
        ViewModelModule.class,
        AccountModule.class,
        HomeModule.class,
        RecognizeModule.class,
        RecordModule.class,
        AiModule.class,
        MineModule.class
})
public interface AppComponent extends AccountDeps, HomeDeps, RecognizeDeps, RecordDeps, AiDeps, MineDeps {

    AppViewModelFactory viewModelFactory();

    UserSession userSession();

    SessionEvents sessionEvents();

    IUserService userService();

    IRecordService recordService();

    @Component.Factory
    interface Factory {
        AppComponent create(@BindsInstance Application application);
    }
}
