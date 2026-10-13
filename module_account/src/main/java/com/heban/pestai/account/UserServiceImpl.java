package com.heban.pestai.account;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.common.vo.UserVO;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * IUserService 实现：读写 UserSession，供其他组件通过 ServiceHub 调用。
 */
@Singleton
public class UserServiceImpl implements IUserService {

    private final UserSession session;

    @Inject
    public UserServiceImpl(UserSession session) {
        this.session = session;
    }

    @Nullable
    @Override
    public UserVO currentUser() {
        return session.isLoggedIn() ? session.getUser() : null;
    }

    @Override
    public boolean isLoggedIn() {
        return session.isLoggedIn();
    }

    @NonNull
    @Override
    public LiveData<UserVO> userChanges() {
        return session.userChanges();
    }

    @Override
    public void logout() {
        session.clear();
    }
}
