package com.heban.pestai.common.service;

import androidx.annotation.NonNull;

import com.heban.pestai.common.vo.RecordItemVO;

import java.util.List;

import io.reactivex.rxjava3.core.Single;

/**
 * 识别记录服务（module_record 实现，PestApp 启动时注册到 ServiceHub）。
 * 新增识别、重新识别、删除记录后调用 notifyChanged()，首页、我的页、识别记录页据此刷新。
 */
public interface IRecordService {

    /** 新增识别、重新识别、删除记录后调用；监听回调统一在主线程执行 */
    void notifyChanged();

    void addOnChangedListener(@NonNull OnRecordChangedListener listener);

    /** 在 ViewModel 的 onCleared() 或 Fragment 的 onDestroyView() 中移除，避免泄漏 */
    void removeOnChangedListener(@NonNull OnRecordChangedListener listener);

    /** 最近识别（AI 问答页「继续追问」卡片），在 io 线程执行，调用方自行 observeOn 主线程 */
    @NonNull
    Single<List<RecordItemVO>> recent(int size);

    /** 单条记录摘要（Q02 引用卡），在 io 线程执行 */
    @NonNull
    Single<RecordItemVO> get(long id);

    interface OnRecordChangedListener {
        void onRecordChanged();
    }
}
