package com.heban.pestai.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 服务器地址改写：用户在「关于」页设置了自定义服务器地址（{@link ServerAddress}）时，把发往默认服务器
 * （协议、主机、端口都与 BuildConfig.BASE_URL 相同）的请求改为发往当前地址，路径与参数不变；其他地址的请求不处理。
 * 作为第一个拦截器加入 NetworkModule 的两个 OkHttpClient（普通、流式），Retrofit 的 baseUrl 保持默认地址。
 * <p>当前地址解析成的 HttpUrl 按字符串缓存，地址不变时每个请求只有一次 volatile 读和一次字符串比较。</p>
 */
@Singleton
public class ServerUrlInterceptor implements Interceptor {

    private final ServerAddress serverAddress;
    @Nullable
    private final HttpUrl defaultUrl;
    @Nullable
    private volatile Target target;

    @Inject
    public ServerUrlInterceptor(ServerAddress serverAddress) {
        this.serverAddress = serverAddress;
        this.defaultUrl = HttpUrl.parse(serverAddress.getDefault());
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        HttpUrl to = currentTarget();
        return chain.proceed(to == null ? request : rewrite(request, defaultUrl, to));
    }

    /** 当前地址；与默认地址相同时返回 null（不改写） */
    @Nullable
    private HttpUrl currentTarget() {
        String current = serverAddress.current();
        if (current.equals(serverAddress.getDefault())) {
            return null;
        }
        Target cached = target;
        if (cached == null || !cached.source.equals(current)) {
            cached = new Target(current, HttpUrl.parse(current));
            target = cached;
        }
        return cached.url;
    }

    /**
     * 把发往 {@code from} 的请求改为发往 {@code to}：只替换协议、主机、端口；请求地址不属于 {@code from} 时原样返回。
     */
    @NonNull
    static Request rewrite(@NonNull Request request, @Nullable HttpUrl from, @Nullable HttpUrl to) {
        if (from == null || to == null) {
            return request;
        }
        HttpUrl url = request.url();
        if (!sameServer(url, from) || sameServer(url, to)) {
            return request;
        }
        HttpUrl rewritten = url.newBuilder()
                .scheme(to.scheme())
                .host(to.host())
                .port(to.port())
                .build();
        return request.newBuilder().url(rewritten).build();
    }

    static boolean sameServer(@NonNull HttpUrl a, @NonNull HttpUrl b) {
        return a.scheme().equals(b.scheme()) && a.host().equals(b.host()) && a.port() == b.port();
    }

    /** 地址字符串与解析结果成对缓存，保证读到的两者一致 */
    private static final class Target {
        final String source;
        @Nullable
        final HttpUrl url;

        Target(String source, @Nullable HttpUrl url) {
            this.source = source;
            this.url = url;
        }
    }
}
