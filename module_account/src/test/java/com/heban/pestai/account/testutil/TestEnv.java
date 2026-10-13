package com.heban.pestai.account.testutil;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.session.UserSession;

import java.util.ArrayList;
import java.util.List;

/**
 * 单元测试环境：真实的 AccountRepository + UserSession（内存 SharedPreferences）+ 假 AuthApi。
 */
public class TestEnv {

    public final FakeContext context = new FakeContext();
    public final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .create();
    public final FakeAuthApi api = new FakeAuthApi();
    public final UserSession session;
    public final AccountRepository repository;

    public TestEnv() {
        session = new UserSession(context, gson);
        repository = new AccountRepository(api, session, new ImageCompressor(context), context);
    }

    /** 本地已有登录令牌（模拟上次登录） */
    public TestEnv withToken(String token) {
        context.prefs.edit().putString(UserSession.KEY_TOKEN, token).apply();
        return this;
    }

    /** 本地已登录（令牌 + user_json，手机号 13800005678） */
    public TestEnv withLogin() {
        session.saveLogin(FakeAuthApi.login(false));
        return this;
    }

    public TestEnv withOnboardingDone() {
        session.setOnboardingDone(true);
        return this;
    }

    public String token() {
        return context.prefs.getString(UserSession.KEY_TOKEN, null);
    }

    public String userJson() {
        return context.prefs.getString(UserSession.KEY_USER_JSON, null);
    }

    /** 观察 LiveData 并记录每次的值（让 MediatorLiveData / Transformations 处于活跃状态） */
    public static <T> List<T> record(LiveData<T> liveData) {
        List<T> values = new ArrayList<>();
        liveData.observeForever(values::add);
        return values;
    }

    /** 把 ViewModel 放进 ViewModelStore；调用返回值的 clear() 即触发 onCleared()（模拟页面销毁） */
    @NonNull
    public static <T extends ViewModel> ViewModelStore attach(@NonNull T viewModel) {
        ViewModelStore store = new ViewModelStore();
        new ViewModelProvider(store, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <V extends ViewModel> V create(@NonNull Class<V> modelClass) {
                return (V) viewModel;
            }
        }).get(viewModel.getClass());
        return store;
    }

    /** 激活后取当前值 */
    public static <T> T value(LiveData<T> liveData) {
        if (!liveData.hasObservers()) {
            liveData.observeForever(v -> {
            });
        }
        return liveData.getValue();
    }
}
