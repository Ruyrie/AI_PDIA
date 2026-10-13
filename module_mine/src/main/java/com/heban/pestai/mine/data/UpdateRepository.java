package com.heban.pestai.mine.data;

import androidx.annotation.NonNull;

import com.heban.pestai.common.vo.AppVersionVO;
import com.heban.pestai.network.ApiException;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 预留：检查新版本（模块09 技术准备文档 2.7.9）。v1.0「检查更新」只提示「功能尚未开放」，本类不被任何页面调用。
 * 启用时由 AboutViewModel（手动检查）或 MainActivity（启动检查）调用 {@link #checkLatest()}，
 * 再用 {@link UpdateType#decide(long, AppVersionVO, long)} 决定提示方式。
 */
@Singleton
public class UpdateRepository {

    private final MineApi api;
    private final AppVersion appVersion;

    @Inject
    public UpdateRepository(MineApi api, AppVersion appVersion) {
        this.api = api;
        this.appVersion = appVersion;
    }

    /** GET app/version；服务器没有配置版本时 data 为 null，Single 不能发射空值，因此返回 Maybe，为空直接完成（视为没有更新） */
    @NonNull
    public Maybe<AppVersionVO> checkLatest() {
        return api.latestVersion(appVersion.code())
                .onErrorResumeNext(t -> Single.error(ApiException.from(t)))
                .flatMapMaybe(response -> {
                    if (!response.isSuccess()) {
                        return Maybe.<AppVersionVO>error(ApiException.of(response.getCode(), response.getMessage()));
                    }
                    AppVersionVO data = response.getData();
                    return data == null ? Maybe.<AppVersionVO>empty() : Maybe.just(data);
                })
                .subscribeOn(Schedulers.io());
    }
}
