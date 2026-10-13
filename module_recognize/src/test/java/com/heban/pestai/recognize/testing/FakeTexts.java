package com.heban.pestai.recognize.testing;

import androidx.annotation.NonNull;

import com.heban.pestai.recognize.util.TextSource;

/**
 * 假文案源：返回「#资源 id」，带参数时追加「:参数」，测试中用 {@code texts.get(R.string.xxx)} 生成期望值。
 */
public class FakeTexts implements TextSource {

    @NonNull
    @Override
    public String get(int id, @NonNull Object... args) {
        StringBuilder sb = new StringBuilder("#").append(id);
        for (Object arg : args) {
            sb.append(':').append(arg);
        }
        return sb.toString();
    }
}
