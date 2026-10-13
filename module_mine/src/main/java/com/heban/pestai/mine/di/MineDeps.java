package com.heban.pestai.mine.di;

/**
 * module_mine 从全局依赖图中获取 ViewModel 以外对象的入口（AppComponent 继承本接口）。
 * 例如 Service、Adapter 等无法通过 ViewModel 工厂获得依赖的类：
 * <pre>
 * MineDeps deps = AppHost.deps(context, MineDeps.class);
 * </pre>
 * 方法名请加组件前缀（如 mineRepository()），避免与其他组件的 Deps 接口重名。
 */
public interface MineDeps {
}
