package com.heban.pestai.mine.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * M01「未生成账号恢复码」提醒卡片的关闭记录（本组件自己的 SharedPreferences：mine_recovery_reminder）。
 * 按用户 ID 记录关闭时刻，同一台手机切换账号互不影响；退出登录时保留。只在主线程读写，apply() 异步落盘。
 */
@Singleton
public class RecoveryReminderStore {

    static final String PREFS_NAME = "mine_recovery_reminder";
    private static final String KEY_DISMISSED_PREFIX = "dismissed_at_";

    /** 注入的是 Application Context（壳工程 AppModule 绑定） */
    private final Context context;
    @Nullable
    private SharedPreferences prefs;

    @Inject
    public RecoveryReminderStore(Context context) {
        this.context = context;
    }

    /** 该用户上次关闭提醒的时刻（System.currentTimeMillis()）；从未关闭过为 0 */
    public long dismissedAt(long userId) {
        return prefs().getLong(KEY_DISMISSED_PREFIX + userId, 0L);
    }

    public void setDismissedAt(long userId, long millis) {
        prefs().edit().putLong(KEY_DISMISSED_PREFIX + userId, millis).apply();
    }

    @NonNull
    private SharedPreferences prefs() {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return prefs;
    }
}
