package com.heban.pestai.network.di;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.heban.pestai.network.AuthInterceptor;
import com.heban.pestai.network.ServerUrlInterceptor;

import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 网络层单例（公共数据 03「六、依赖注入」）：Gson、OkHttpClient、流式 OkHttpClient、Retrofit。
 * 需要壳工程提供 {@code @Named(BASE_URL) String}、{@code @Named(DEFAULT_SERVER) String} 与 {@code @Named(DEBUG) boolean}。
 * 两个 OkHttpClient 的第一个拦截器都是 {@link ServerUrlInterceptor}：用户在「关于」页改了服务器地址后，
 * 发往默认地址的请求改发到新地址（Retrofit 的 baseUrl 不变）。
 * 各业务组件在自己的 Dagger Module 中用 Retrofit 创建 XxxApi。
 */
@Module
public final class NetworkModule {

    /** Retrofit baseUrl：{BASE_URL}/pestai/v1/ */
    public static final String BASE_URL = "baseUrl";
    /** 默认服务器地址（BuildConfig.BASE_URL 去掉末尾的 /，如 http://10.0.2.2:8080），供 ServerAddress 使用 */
    public static final String DEFAULT_SERVER = "defaultServer";
    /** 是否调试版（调试版打印请求与响应体） */
    public static final String DEBUG = "debug";
    /** AI 问答流式回答专用 OkHttpClient：读取超时 120 秒，日志只打印请求头 */
    public static final String STREAM = "stream";

    public static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private NetworkModule() {
    }

    @Provides
    @Singleton
    static Gson provideGson() {
        return new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .setDateFormat(DATE_FORMAT)
                .create();
    }

    @Provides
    @Singleton
    static OkHttpClient provideOkHttpClient(ServerUrlInterceptor serverUrlInterceptor, AuthInterceptor authInterceptor,
                                            @Named(DEBUG) boolean debug) {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(debug ? HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.NONE);
        return new OkHttpClient.Builder()
                .addInterceptor(serverUrlInterceptor)
                .addInterceptor(authInterceptor)
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Provides
    @Singleton
    @Named(STREAM)
    static OkHttpClient provideStreamOkHttpClient(ServerUrlInterceptor serverUrlInterceptor,
                                                  AuthInterceptor authInterceptor, @Named(DEBUG) boolean debug) {
        // 打印响应体会先读完整段响应，导致逐字显示失效，因此只打印请求头
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(debug ? HttpLoggingInterceptor.Level.HEADERS : HttpLoggingInterceptor.Level.NONE);
        return new OkHttpClient.Builder()
                .addInterceptor(serverUrlInterceptor)
                .addInterceptor(authInterceptor)
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Provides
    @Singleton
    static Retrofit provideRetrofit(OkHttpClient client, Gson gson, @Named(BASE_URL) String baseUrl) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
                .build();
    }
}
