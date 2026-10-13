package com.heban.pestai.recognize.testing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.recognize.data.RecognizeRepository;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

/**
 * 假 Repository：不访问网络与文件，返回预设结果并记录调用次数。
 */
public class FakeRepository extends RecognizeRepository {

    public Single<RecognitionVO> detailResult = Single.error(new IllegalStateException("未设置 detailResult"));
    public Single<RecognitionVO> retryResult = Single.error(new IllegalStateException("未设置 retryResult"));
    public boolean saveToAlbum;
    public boolean readable = true;

    public int detailCalls;
    public int retryCalls;
    public int cleanCalls;
    public final List<String> savedToAlbum = new ArrayList<>();
    public final List<Long> retryIds = new ArrayList<>();

    public FakeRepository() {
        super(null, null, null, null);
    }

    @NonNull
    @Override
    public Single<RecognitionVO> getDetail(long id) {
        detailCalls++;
        return detailResult;
    }

    @NonNull
    @Override
    public Single<RecognitionVO> retry(long id) {
        retryCalls++;
        retryIds.add(id);
        return retryResult;
    }

    @Override
    public boolean isSaveToAlbum() {
        return saveToAlbum;
    }

    @NonNull
    @Override
    public Completable saveToAlbum(@NonNull String sourceUri) {
        savedToAlbum.add(sourceUri);
        return Completable.complete();
    }

    @NonNull
    @Override
    public Completable cleanOldFiles() {
        cleanCalls++;
        return Completable.complete();
    }

    @NonNull
    @Override
    public Single<Boolean> isReadable(@Nullable String uri) {
        return Single.just(readable);
    }
}
