package com.heban.pestai.record.di;

/**
 * module_record 从全局依赖图中获取 ViewModel 以外对象的入口（AppComponent 继承本接口）。
 * 例如 Service、Adapter 等无法通过 ViewModel 工厂获得依赖的类：
 * <pre>
 * RecordDeps deps = AppHost.deps(context, RecordDeps.class);
 * </pre>
 * 方法名请加组件前缀（如 recordRepository()），避免与其他组件的 Deps 接口重名。
 */
public interface RecordDeps {
}
