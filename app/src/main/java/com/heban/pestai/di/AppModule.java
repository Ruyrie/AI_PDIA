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
 * BASE_URL 是默认服务器地址；用户在「关于」页改过服务器地址时，由 lib_network 的 ServerUrlInterceptor 改写请求。
 */
@Module
public abstract class AppModule {

    @Binds
    abstract Context bindContext(Application application);

    /** Retrofit baseUrl：{BASE_URL}/pestai/v1/ */
    @Provides
    @Named(NetworkModule.BASE_URL)
    static String provideBaseUrl() {
        return trimTrailingSlashes(BuildConfig.BASE_URL) + "/pestai/v1/";
    }

    /** 默认服务器地址：BASE_URL 去掉末尾的 /，如 http://10.0.2.2:8080（ServerAddress 使用） */
    @Provides
    @Named(NetworkModule.DEFAULT_SERVER)
    static String provideDefaultServer() {
        return trimTrailingSlashes(BuildConfig.BASE_URL);
    }

    private static String trimTrailingSlashes(String url) {
        String base = url;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    @Provides
    @Named(NetworkModule.DEBUG)
    static boolean provideDebug() {
        return BuildConfig.DEBUG;
    }
}
