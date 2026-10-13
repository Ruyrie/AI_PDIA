package com.heban.pestai.mine.testutil;

import androidx.lifecycle.LiveData;

import java.util.ArrayList;
import java.util.List;

/**
 * LiveData 测试辅助：Transformations.map() / MediatorLiveData 只有在有活跃观察者时才计算，
 * 先 observeForever 再读取当前值。需配合 InstantTaskExecutorRule 使用。
 */
public final class LiveDataTestUtil {

    private LiveDataTestUtil() {
    }

    /** 保持观察并返回当前值 */
    public static <T> T value(LiveData<T> liveData) {
        liveData.observeForever(v -> {
        });
        return liveData.getValue();
    }

    /** 记录之后发出的全部值（一次性事件） */
    public static <T> List<T> record(LiveData<T> liveData) {
        List<T> values = new ArrayList<>();
        liveData.observeForever(values::add);
        return values;
    }
}
