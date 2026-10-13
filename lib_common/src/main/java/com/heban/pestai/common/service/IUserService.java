package com.heban.pestai.common.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.heban.pestai.common.vo.UserVO;

/**
 * 用户服务（module_account 实现，PestApp 启动时注册到 ServiceHub）。
 */
public interface IUserService {

    /** 当前用户，未登录返回 null */
    @Nullable
    UserVO currentUser();

    /** 是否已登录（游客为 false） */
    boolean isLoggedIn();

    /** 资料修改、退出登录时发出新值，我的页等监听后刷新 */
    @NonNull
    LiveData<UserVO> userChanges();

    /** 清除 UserSession 中的 token、user_json、is_guest */
    void logout();
}
