package com.heban.pestai.di;

import android.app.Application;
import android.content.Context;

import com.heban.pestai.BuildConfig;
import com.heban.pestai.network.di.NetworkModule;

import javax.inject.Named;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

/**
 * 壳工程提供的基础对象：Application Context、BASE_URL（local.properties → BuildConfig）、调试开关。
 */
@Module
public abstract class AppModule {

    @Binds
    abstract Context bindContext(Application application);

    /** Retrofit baseUrl：{BASE_URL}/pestai/v1/ */
    @Provides
    @Named(NetworkModule.BASE_URL)
    static String provideBaseUrl() {
        String base = BuildConfig.BASE_URL;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/pestai/v1/";
    }

    @Provides
    @Named(NetworkModule.DEBUG)
    static boolean provideDebug() {
        return BuildConfig.DEBUG;
    }
}
