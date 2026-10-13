package com.heban.pestai.common.service;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组件间服务注册表。PestApp.onCreate() 中 register(IUserService.class, impl) 等；
 * 其他组件调用 get(IUserService.class) 获取实现，只依赖 lib_common 中的接口。
 */
public final class ServiceHub {

    private static final Map<Class<?>, Object> SERVICES = new ConcurrentHashMap<>();

    private ServiceHub() {
    }

    public static <T> void register(@NonNull Class<T> type, @NonNull T impl) {
        SERVICES.put(type, impl);
    }

    @NonNull
    public static <T> T get(@NonNull Class<T> type) {
        Object impl = SERVICES.get(type);
        if (impl == null) {
            throw new IllegalStateException(type.getSimpleName() + " 未注册，请检查 PestApp.onCreate()");
        }
        return type.cast(impl);
    }
}
