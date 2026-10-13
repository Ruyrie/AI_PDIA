package com.heban.pestai.home.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.RxResult;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 首页数据：本地缓存的 UserVO、游客判断、user/me 与 recognitions/recent 两个接口（模块04 技术准备文档 2.8 第 2 点）。
 */
@Singleton
public class HomeRepository {

    private final HomeApi api;
    private final UserSession userSession;

    @Inject
    public HomeRepository(HomeApi api, UserSession userSession) {
        this.api = api;
        this.userSession = userSession;
    }

    /** user_json 中缓存的用户资料；为空或解析失败返回 null。SharedPreferences 已在内存中，可在主线程调用 */
    @Nullable
    public UserVO cachedUser() {
        return userSession.getUser();
    }

    /** is_guest 为 true 或 token 为空时视为游客 */
    public boolean isGuest() {
        return !userSession.isLoggedIn();
    }

    /** 资料修改（我的页编辑资料、user/me 写回）、退出登录时发出新值 */
    @NonNull
    public LiveData<UserVO> userChanges() {
        return userSession.userChanges();
    }

    /** GET user/me（io 线程）；成功后把最新资料写回 user_json */
    @NonNull
    public Single<UserVO> fetchMe() {
        return api.me()
                .compose(RxResult.transform())
                .doOnSuccess(userSession::saveUser)
                .subscribeOn(Schedulers.io());
    }

    /** GET recognitions/recent（io 线程）；data 为 null 按空列表处理，超过 size 条只保留前 size 条 */
    @NonNull
    public Single<List<RecordItemVO>> fetchRecent(int size) {
        return api.recent(size)
                .map(response -> {
                    if (response.isSuccess() && response.getData() == null) {
                        response.setData(new ArrayList<>());
                    }
                    return response;
                })
                .compose(RxResult.transform())
                .map(list -> limit(list, size))
                .subscribeOn(Schedulers.io());
    }

    /** null 转为空列表，最多保留前 size 条，并复制为新列表（ListAdapter 据此识别为新列表） */
    @NonNull
    public static List<RecordItemVO> limit(@Nullable List<RecordItemVO> list, int size) {
        List<RecordItemVO> result = new ArrayList<>();
        if (list == null) {
            return result;
        }
        for (RecordItemVO item : list) {
            if (result.size() >= size) {
                break;
            }
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }
}
