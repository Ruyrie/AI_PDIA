package com.heban.pestai.recognize.util;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

/**
 * ViewModel 读取 strings.xml 文案的入口。ViewModel 不持有 Context，单元测试中替换为假实现。
 */
public interface TextSource {

    @NonNull
    String get(@StringRes int id, @NonNull Object... args);
}
