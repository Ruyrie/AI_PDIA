package com.heban.pestai.home.di;

/**
 * module_home 从全局依赖图中获取 ViewModel 以外对象的入口（AppComponent 继承本接口）。
 * 例如 Service、Adapter 等无法通过 ViewModel 工厂获得依赖的类：
 * <pre>
 * HomeDeps deps = AppHost.deps(context, HomeDeps.class);
 * </pre>
 * 方法名请加组件前缀（如 homeRepository()），避免与其他组件的 Deps 接口重名。
 * 首页目前所有依赖都经 HomeViewModel 注入，暂无需要声明的方法。
 */
public interface HomeDeps {
}
