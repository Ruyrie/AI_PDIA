package com.heban.pestai.mine.data;

import androidx.annotation.NonNull;

import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.UserStatsVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.RxResult;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 我的页资料、统计与退出登录（模块09 技术准备文档 2.3 / 2.7.1 / 2.7.5）。
 * 每个方法各自 subscribeOn(io)：Single.zip 合并 me() 与 stats() 时两个请求在不同线程同时发出。
 */
@Singleton
public class MineRepository {

    private final MineApi api;
    private final UserSession session;

    @Inject
    public MineRepository(MineApi api, UserSession session) {
        this.api = api;
        this.session = session;
    }

    /** GET user/me；成功后资料有变化时写回 UserSession 的 user_json（并通过 userChanges() 通知其他组件） */
    @NonNull
    public Single<UserVO> me() {
        return api.me()
                .compose(RxResult.transform())
                .doOnSuccess(this::cacheUser)
                .subscribeOn(Schedulers.io());
    }

    /** GET user/stats */
    @NonNull
    public Single<UserStatsVO> stats() {
        return api.stats()
                .compose(RxResult.transform())
                .subscribeOn(Schedulers.io());
    }

    /** GET user/recovery-code：设置页「账号恢复码」行的状态 */
    @NonNull
    public Single<RecoveryCodeVO> recoveryCode() {
        return api.recoveryCode()
                .compose(RxResult.transform())
                .subscribeOn(Schedulers.io());
    }

    /**
     * POST auth/logout。data 为 null，RxJava 不能发射空值，因此直接 ignoreElement() 只关心请求是否结束；
     * 超时与失败的容错由调用方（SettingsViewModel）处理。
     */
    @NonNull
    public Completable logout() {
        return api.logout()
                .ignoreElement()
                .subscribeOn(Schedulers.io());
    }

    private void cacheUser(@NonNull UserVO user) {
        // 期间已退出登录则不再写回；内容未变化时不重复写盘、不发出 userChanges
        if (session.isLoggedIn() && !user.equals(session.getUser())) {
            session.saveUser(user);
        }
    }
}
