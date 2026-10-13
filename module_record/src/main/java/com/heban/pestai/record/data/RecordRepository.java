package com.heban.pestai.record.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.network.PageVO;
import com.heban.pestai.network.RxResult;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 识别记录仓库：封装 RecordApi，统一在 io 线程执行并解包 ApiResponse，向 ViewModel 返回 VO。
 * 错误统一为 ApiException（网络不可用、超时、业务错误码）。
 */
@Singleton
public class RecordRepository {

    private final RecordApi api;

    @Inject
    public RecordRepository(RecordApi api) {
        this.api = api;
    }

    /** GET recognitions；mode 为 null 表示「全部」 */
    @NonNull
    public Single<PageVO<RecordItemVO>> list(@Nullable String mode, int page, int size) {
        return api.list(mode, page, size)
                .compose(RxResult.transform())
                .subscribeOn(Schedulers.io());
    }

    /** DELETE recognitions/{id}：code 为 0 时正常完成，否则以 ApiException 结束 */
    @NonNull
    public Completable delete(long id) {
        return api.delete(id)
                .to(RxResult.completable())
                .subscribeOn(Schedulers.io());
    }
}
