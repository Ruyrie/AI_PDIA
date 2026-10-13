package com.heban.pestai.mine.data;

import com.heban.pestai.common.session.UserSession;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 本机设置「识别照片保存到相册」（SharedPreferences pest_session 的 save_to_album，默认 false）。
 * 模块05 拍照成功后读取同一个键；退出登录时保留。单独封装便于 SettingsViewModel 用假实现测试。
 */
@Singleton
public class SettingsRepository {

    private final UserSession session;

    @Inject
    public SettingsRepository(UserSession session) {
        this.session = session;
    }

    public boolean isSaveToAlbum() {
        return session.isSaveToAlbum();
    }

    /** apply() 先同步更新内存再后台写盘，可在主线程调用 */
    public void setSaveToAlbum(boolean save) {
        session.setSaveToAlbum(save);
    }
}
