package com.heban.pestai.network;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.heban.pestai.network.di.NetworkModule;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import okhttp3.HttpUrl;

/**
 * 服务器地址。默认地址来自壳工程 BuildConfig.BASE_URL（local.properties），用户可在「我的 → 关于」连续点击版本号 5 次后
 * 改为其他地址（如部署到云服务器的公网 IP），保存在 SharedPreferences pest_server 的 server_url 中，退出登录时保留。
 * <p>地址统一为「协议://主机[:端口]」，如 http://47.100.1.2、http://47.100.1.2:8080（默认端口不写）。
 * Retrofit 的 baseUrl 始终是默认地址，由 {@link ServerUrlInterceptor} 在请求发出前把协议、主机、端口替换为当前地址，
 * 修改后立即生效，不需要重建 Retrofit。</p>
 * <p>可在任意线程调用：首次读取时加载 SharedPreferences，之后只读内存中的 volatile 字段。</p>
 */
@Singleton
public class ServerAddress {

    public static final String PREFS_NAME = "pest_server";
    public static final String KEY_SERVER_URL = "server_url";

    /** 注入的是 Application Context（壳工程 AppModule 绑定）；测试子类可传 null 并重写读写方法 */
    @Nullable
    private final Context context;
    private final String defaultServer;
    private final Object lock = new Object();
    @Nullable
    private SharedPreferences prefs;
    private volatile boolean loaded;
    /** 自定义地址；null 表示使用默认地址 */
    @Nullable
    private volatile String custom;

    @Inject
    public ServerAddress(@Nullable Context context, @Named(NetworkModule.DEFAULT_SERVER) String defaultServer) {
        this.context = context;
        String normalized = normalize(defaultServer);
        // BASE_URL 带路径等非常规写法时保留原值（只去掉末尾的 /），改写请求时只比较协议、主机、端口
        this.defaultServer = normalized != null ? normalized : trimTrailingSlashes(defaultServer);
    }

    /** 当前使用的地址：自定义地址，没有时为默认地址 */
    @NonNull
    public String current() {
        String value = custom();
        return value != null ? value : defaultServer;
    }

    /** 默认地址（BuildConfig.BASE_URL），如 http://10.0.2.2:8080 */
    @NonNull
    public String getDefault() {
        return defaultServer;
    }

    /** 是否使用了与默认地址不同的自定义地址 */
    public boolean isCustom() {
        return custom() != null;
    }

    /**
     * 保存自定义地址（先按 {@link #normalize(String)} 规范化）；与默认地址相同时等同于 {@link #reset()}。
     *
     * @return 地址格式不正确时为 false（不做任何修改）
     */
    public boolean set(@Nullable String address) {
        String normalized = normalize(address);
        if (normalized == null) {
            return false;
        }
        if (normalized.equals(defaultServer)) {
            reset();
            return true;
        }
        synchronized (lock) {
            writeSaved(normalized);
            custom = normalized;
            loaded = true;
        }
        return true;
    }

    /** 恢复默认地址 */
    public void reset() {
        synchronized (lock) {
            writeSaved(null);
            custom = null;
            loaded = true;
        }
    }

    /**
     * 把用户输入规范化为「协议://主机[:端口]」：去掉首尾空白，中文冒号、句号换成英文；没写协议时补 http://；
     * 只接受 http / https，必须有主机，不能带路径（末尾的 / 除外）、查询参数、锚点和用户名密码；端口为协议默认端口时省略。
     * 例：「47.100.1.2」「47.100.1.2:8080」「http://47.100.1.2/」「example.com」。
     *
     * @return 格式不正确时为 null
     */
    @Nullable
    public static String normalize(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim().replace('：', ':').replace('。', '.');
        if (value.isEmpty()) {
            return null;
        }
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        // HttpUrl.parse() 只接受 http / https，主机为空或端口越界时返回 null
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || url.host().isEmpty()) {
            return null;
        }
        if (!"/".equals(url.encodedPath()) || url.encodedQuery() != null || url.encodedFragment() != null) {
            return null;
        }
        if (!url.encodedUsername().isEmpty() || !url.encodedPassword().isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(url.scheme()).append("://");
        String host = url.host();
        if (host.indexOf(':') >= 0) {
            // IPv6 地址需要方括号
            builder.append('[').append(host).append(']');
        } else {
            builder.append(host);
        }
        if (url.port() != HttpUrl.defaultPort(url.scheme())) {
            builder.append(':').append(url.port());
        }
        return builder.toString();
    }

    /** 读取保存的地址（未保存为 null） */
    @VisibleForTesting
    @Nullable
    protected String readSaved() {
        return prefs().getString(KEY_SERVER_URL, null);
    }

    /** 保存地址；null 表示删除（恢复默认）。apply() 先更新内存再后台写盘，可在主线程调用 */
    @VisibleForTesting
    protected void writeSaved(@Nullable String value) {
        if (value == null) {
            prefs().edit().remove(KEY_SERVER_URL).apply();
        } else {
            prefs().edit().putString(KEY_SERVER_URL, value).apply();
        }
    }

    @Nullable
    private String custom() {
        if (!loaded) {
            synchronized (lock) {
                if (!loaded) {
                    String saved = normalize(readSaved());
                    custom = saved == null || saved.equals(defaultServer) ? null : saved;
                    loaded = true;
                }
            }
        }
        return custom;
    }

    @NonNull
    private SharedPreferences prefs() {
        if (prefs == null) {
            if (context == null) {
                throw new IllegalStateException("ServerAddress 缺少 Context");
            }
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    @NonNull
    private static String trimTrailingSlashes(@Nullable String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
