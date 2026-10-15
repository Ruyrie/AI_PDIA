package com.heban.pestai.ai.compose;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 记住输入栏的输入方式（文字 / 语音），Q01 / Q02 共用：SharedPreferences {@value #PREFS_NAME} 中的
 * {@value #KEY_VOICE_MODE}（true 为语音）。
 * <ul>
 *     <li>只记录用户最后一次主动切换（点按麦克风 / 键盘图标）；识别成功后临时切到文字不记录；</li>
 *     <li>与账号无关，退出登录不清除；没有记录时为文字输入；</li>
 *     <li>状态逻辑见 {@link InputMode}，本类只负责读写。</li>
 * </ul>
 */
@Singleton
public final class InputModeStore {

    public static final String PREFS_NAME = "ai_composer";
    public static final String KEY_VOICE_MODE = "voice_mode";

    private final SharedPreferences prefs;

    @Inject
    public InputModeStore(@NonNull Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    /** 单元测试传入内存实现 */
    @VisibleForTesting
    public InputModeStore(@NonNull SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** 记住的输入方式是语音（没有记录时为 false：文字） */
    public boolean isVoiceMode() {
        return prefs.getBoolean(KEY_VOICE_MODE, false);
    }

    /** 记住输入方式（与已记住的相同时不写入） */
    public void setVoiceMode(boolean voice) {
        if (prefs.contains(KEY_VOICE_MODE) && isVoiceMode() == voice) {
            return;
        }
        prefs.edit().putBoolean(KEY_VOICE_MODE, voice).apply();
    }
}
