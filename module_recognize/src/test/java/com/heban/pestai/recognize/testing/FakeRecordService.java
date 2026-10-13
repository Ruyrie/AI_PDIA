package com.heban.pestai.recognize.testing;

import androidx.annotation.NonNull;

import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.vo.RecordItemVO;

import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

/**
 * 假识别记录服务：只统计 notifyChanged() 的调用次数。
 */
public class FakeRecordService implements IRecordService {

    public int changedCount;

    @Override
    public void notifyChanged() {
        changedCount++;
    }

    @Override
    public void addOnChangedListener(@NonNull OnRecordChangedListener listener) {
    }

    @Override
    public void removeOnChangedListener(@NonNull OnRecordChangedListener listener) {
    }

    @NonNull
    @Override
    public Single<List<RecordItemVO>> recent(int size) {
        return Single.just(Collections.emptyList());
    }

    @NonNull
    @Override
    public Single<RecordItemVO> get(long id) {
        return Single.error(new UnsupportedOperationException());
    }
}
