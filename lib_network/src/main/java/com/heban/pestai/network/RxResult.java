package com.heban.pestai.network;

import androidx.annotation.NonNull;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleConverter;
import io.reactivex.rxjava3.core.SingleTransformer;

/**
 * 统一解包 ApiResponse 的 RxJava 转换器：code 为 0 时取出 data（VO），否则抛出 ApiException；
 * 网络异常、HTTP 异常也统一转换为 ApiException。
 *
 * <pre>
 * api.recent(3).compose(RxResult.transform())          // Single&lt;List&lt;RecordItemVO&gt;&gt;
 * api.sendSms(body).to(RxResult.completable())          // data 为 null 的接口
 * </pre>
 */
public final class RxResult {

    private RxResult() {
    }

    /** 有返回数据的接口；code 为 0 但 data 为 null 时抛出 50001 */
    @NonNull
    public static <T> SingleTransformer<ApiResponse<T>, T> transform() {
        return upstream -> upstream
                .onErrorResumeNext(t -> Single.error(ApiException.from(t)))
                .flatMap(response -> {
                    if (response == null) {
                        return Single.error(ApiException.of(ApiException.SERVER_ERROR));
                    }
                    if (!response.isSuccess()) {
                        return Single.error(ApiException.of(response.getCode(), response.getMessage()));
                    }
                    if (response.getData() == null) {
                        return Single.error(ApiException.of(ApiException.SERVER_ERROR));
                    }
                    return Single.just(response.getData());
                });
    }

    /** data 为 null 的接口（发送验证码、重置密码、删除记录、退出登录等） */
    @NonNull
    public static <T> SingleConverter<ApiResponse<T>, Completable> completable() {
        return upstream -> upstream
                .onErrorResumeNext(t -> Single.error(ApiException.from(t)))
                .flatMapCompletable(response -> {
                    if (response != null && response.isSuccess()) {
                        return Completable.complete();
                    }
                    return Completable.error(response == null
                            ? ApiException.of(ApiException.SERVER_ERROR)
                            : ApiException.of(response.getCode(), response.getMessage()));
                });
    }
}
