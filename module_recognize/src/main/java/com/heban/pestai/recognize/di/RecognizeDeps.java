package com.heban.pestai.recognize.di;

import com.heban.pestai.recognize.data.RecognizeRepository;

/**
 * module_recognize 从全局依赖图中获取 ViewModel 以外对象的入口（AppComponent 继承本接口）。
 * 例如 Service、Adapter 等无法通过 ViewModel 工厂获得依赖的类：
 * <pre>
 * RecognizeDeps deps = AppHost.deps(context, RecognizeDeps.class);
 * </pre>
 * 方法名请加组件前缀（如 recognizeRepository()），避免与其他组件的 Deps 接口重名。
 */
public interface RecognizeDeps {

    /** RecognizeService 上传识别使用 */
    RecognizeRepository recognizeRepository();
}
