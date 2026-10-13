package com.heban.pestai.common.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.heban.pestai.common.vo.LoginVO;
import com.heban.pestai.common.vo.UserVO;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 登录态与本地设置（SharedPreferences：pest_session，公共数据 05「七、本地存储键」）。
 * 由 Dagger 以单例提供，业务代码不直接接触键名。
 */
@Singleton
public class UserSession {

    public static final String PREFS_NAME = "pest_session";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_USER_JSON = "user_json";
    public static final String KEY_ONBOARDING_DONE = "onboarding_done";
    public static final String KEY_SAVE_TO_ALBUM = "save_to_album";
    public static final String KEY_IS_GUEST = "is_guest";

    private final SharedPreferences prefs;
    private final Gson gson;
    private final MutableLiveData<UserVO> userChanges;

    @Inject
    public UserSession(@NonNull Context context, @NonNull Gson gson) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = gson;
        // 构造方法可能在任意线程执行（如首次在 OkHttp 线程注入），用带初值的构造方法而不是 setValue()
        this.userChanges = new MutableLiveData<>(readUser());
    }

    // ---------- 登录态 ----------

    @Nullable
    public String getToken() {
        return prefs.getString(KEY_TOKEN, null);
    }

    public boolean hasToken() {
        return !TextUtils.isEmpty(getToken());
    }

    /** 已登录（有令牌且不是游客） */
    public boolean isLoggedIn() {
        return hasToken() && !isGuest();
    }

    /** 登录 / 注册成功：保存 token、user_json，is_guest 写回 false，并通知其他组件 */
    public void saveLogin(@NonNull LoginVO login) {
        prefs.edit()
                .putString(KEY_TOKEN, login.getToken())
                .putString(KEY_USER_JSON, gson.toJson(login.getUser()))
                .putBoolean(KEY_IS_GUEST, false)
                .apply();
        publish(login.getUser());
    }

    /** 资料刷新或修改后覆盖 user_json，并通知其他组件 */
    public void saveUser(@NonNull UserVO user) {
        prefs.edit().putString(KEY_USER_JSON, gson.toJson(user)).apply();
        publish(user);
    }

    /** 上次登录时缓存的用户资料；解析失败时删除该键并返回 null */
    @Nullable
    public UserVO getUser() {
        return readUser();
    }

    /** 令牌失效：删除 token 与 user_json */
    public void clearLogin() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER_JSON).apply();
        publish(null);
    }

    /** 退出登录 / 需要重新登录：删除 token、user_json、is_guest；保留 onboarding_done、save_to_album */
    public void clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER_JSON).remove(KEY_IS_GUEST).apply();
        publish(null);
    }

    /** 资料修改、退出登录时发出新值（退出时为 null） */
    @NonNull
    public LiveData<UserVO> userChanges() {
        return userChanges;
    }

    // ---------- 游客 ----------

    public boolean isGuest() {
        return prefs.getBoolean(KEY_IS_GUEST, false);
    }

    public void setGuest(boolean guest) {
        prefs.edit().putBoolean(KEY_IS_GUEST, guest).apply();
    }

    // ---------- 本地设置 ----------

    public boolean isOnboardingDone() {
        return prefs.getBoolean(KEY_ONBOARDING_DONE, false);
    }

    public void setOnboardingDone(boolean done) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply();
    }

    public boolean isSaveToAlbum() {
        return prefs.getBoolean(KEY_SAVE_TO_ALBUM, false);
    }

    public void setSaveToAlbum(boolean save) {
        prefs.edit().putBoolean(KEY_SAVE_TO_ALBUM, save).apply();
    }

    // ---------- 内部 ----------

    @Nullable
    private UserVO readUser() {
        String json = prefs.getString(KEY_USER_JSON, null);
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            return gson.fromJson(json, UserVO.class);
        } catch (RuntimeException e) {
            prefs.edit().remove(KEY_USER_JSON).apply();
            return null;
        }
    }

    private void publish(@Nullable UserVO user) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            userChanges.setValue(user);
        } else {
            userChanges.postValue(user);
        }
    }
}
