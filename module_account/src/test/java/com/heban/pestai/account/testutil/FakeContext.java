package com.heban.pestai.account.testutil;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.io.File;

/** JVM 单元测试用的 Context：只提供 ApplicationContext、内存 SharedPreferences 与缓存目录 */
public class FakeContext extends ContextWrapper {

    public final InMemorySharedPreferences prefs = new InMemorySharedPreferences();

    public FakeContext() {
        super(null);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs;
    }

    @Override
    public File getCacheDir() {
        return new File(System.getProperty("java.io.tmpdir"));
    }
}
