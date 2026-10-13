package com.heban.pestai.record;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.network.RxResult;
import com.heban.pestai.record.data.RecordApi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * IRecordService 实现（模块07 技术准备文档 2.7.8、2.8 第 4 点）。
 * <ul>
 *     <li>记录变化通知：监听者保存在 CopyOnWriteArrayList，回调统一在主线程执行；
 *     在主线程调用 notifyChanged() 时同步回调（HistoryViewModel 的 selfNotifying 标记依赖这一点）。</li>
 *     <li>记录读取：recent() / get() 调用 RecordApi，经 RxResult.transform() 取出 VO，在 io 线程执行，
 *     调用方自行 observeOn 主线程。</li>
 * </ul>
 */
@Singleton
public class RecordServiceImpl implements IRecordService {

    private final RecordApi api;
    private final CopyOnWriteArrayList<OnRecordChangedListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Inject
    public RecordServiceImpl(RecordApi api) {
        this.api = api;
    }

    @Override
    public void notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch();
        } else {
            mainHandler.post(this::dispatch);
        }
    }

    private void dispatch() {
        for (OnRecordChangedListener listener : listeners) {
            listener.onRecordChanged();
        }
    }

    @Override
    public void addOnChangedListener(@NonNull OnRecordChangedListener listener) {
        listeners.addIfAbsent(listener);
    }

    @Override
    public void removeOnChangedListener(@NonNull OnRecordChangedListener listener) {
        listeners.remove(listener);
    }

    /** GET recognitions/recent（AI 问答「继续追问」卡片） */
    @NonNull
    @Override
    public Single<List<RecordItemVO>> recent(int size) {
        return api.recent(size)
                .compose(RxResult.transform())
                .subscribeOn(Schedulers.io());
    }

    /** GET recognitions/{id}，只解析 RecordItemVO 的字段（Q02 引用卡） */
    @NonNull
    @Override
    public Single<RecordItemVO> get(long id) {
        return api.summary(id)
                .compose(RxResult.transform())
                .subscribeOn(Schedulers.io());
    }
}
