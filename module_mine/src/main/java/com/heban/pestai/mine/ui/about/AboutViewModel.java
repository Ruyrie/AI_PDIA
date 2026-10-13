package com.heban.pestai.mine.ui.about;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.vo.AppVersionVO;
import com.heban.pestai.mine.data.AppVersion;

import javax.inject.Inject;

/**
 * M03 关于（模块09 技术准备文档 2.4「AboutViewModel」、2.7.8）。
 * v1.0 不注入 UpdateRepository、不请求 app/version：「检查更新」只发出 NOT_OPEN。
 */
public class AboutViewModel extends BaseViewModel {

    private final String versionText;
    /** 预留：检查更新返回的最新版本，v1.0 始终为空 */
    private final MutableLiveData<AppVersionVO> latestVersion = new MutableLiveData<>();
    private final SingleLiveEvent<AboutEvent> event = new SingleLiveEvent<>();

    @Inject
    public AboutViewModel(AppVersion appVersion) {
        this.versionText = "拍照识病虫 · 版本 " + appVersion.name() + "（" + appVersion.code() + "）";
    }

    /** v1.0 只提示「功能尚未开放」，不发网络请求 */
    public void onCheckUpdate() {
        event.setValue(AboutEvent.NOT_OPEN);
    }

    public void onUserAgreementClick() {
        event.setValue(AboutEvent.OPEN_USER_AGREEMENT);
    }

    public void onPrivacyClick() {
        event.setValue(AboutEvent.OPEN_PRIVACY);
    }

    public String getVersionText() {
        return versionText;
    }

    public LiveData<AppVersionVO> getLatestVersion() {
        return latestVersion;
    }

    public LiveData<AboutEvent> getEvent() {
        return event;
    }
}
