package com.heban.pestai.mine.ui.about;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.vo.AppVersionVO;
import com.heban.pestai.mine.data.AppVersion;
import com.heban.pestai.network.ServerAddress;

import java.util.function.LongSupplier;

import javax.inject.Inject;

/**
 * M03 关于（模块09 技术准备文档 2.4「AboutViewModel」、2.7.8）。
 * v1.0 不注入 UpdateRepository、不请求 app/version：「检查更新」只发出 NOT_OPEN。
 * <p>隐藏的服务器设置：1.5 秒内连续点击版本号，第 3、4 次提示「再点 N 次打开服务器设置」，第 5 次打开「服务器地址」对话框。
 * 保存了不同的地址（或从自定义地址恢复默认）后清除本机登录态，页面清栈跳转登录页；游客也能从「我的 → 关于」进入，
 * 连不上服务器、无法登录时可以在这里改地址。</p>
 */
public class AboutViewModel extends BaseViewModel {

    /** 连续点击版本号的次数达到该值时打开服务器设置 */
    static final int TAPS_TO_OPEN = 5;
    /** 两次点击间隔超过该值（毫秒）时重新计数 */
    static final long TAP_INTERVAL_MS = 1500;

    static final String SERVER_PREFIX = "服务器：";

    /** 保存服务器地址的结果 */
    public enum ServerResult {
        /** 格式不正确：对话框显示错误，不关闭 */
        INVALID,
        /** 与当前地址相同：只关闭对话框 */
        UNCHANGED,
        /** 已切换：本机登录态已清除，页面清栈跳转登录页 */
        CHANGED
    }

    private final String versionText;
    private final ServerAddress serverAddress;
    private final IUserService userService;
    /** 预留：检查更新返回的最新版本，v1.0 始终为空 */
    private final MutableLiveData<AppVersionVO> latestVersion = new MutableLiveData<>();
    /** 版本号下方的「服务器：http://…」，只在使用自定义地址时显示 */
    private final MutableLiveData<String> serverText = new MutableLiveData<>();
    private final MutableLiveData<Boolean> customServer = new MutableLiveData<>(false);
    private final SingleLiveEvent<AboutEvent> event = new SingleLiveEvent<>();

    private LongSupplier clock = SystemClock::elapsedRealtime;
    private int tapCount;
    private long lastTapAt;

    @Inject
    public AboutViewModel(AppVersion appVersion, ServerAddress serverAddress, IUserService userService) {
        this.versionText = "拍照识病虫 · 版本 " + appVersion.name() + "（" + appVersion.code() + "）";
        this.serverAddress = serverAddress;
        this.userService = userService;
        refreshServer();
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

    /** 点击版本号：间隔超过 1.5 秒重新计数；第 3、4 次提示剩余次数，第 5 次打开服务器设置 */
    public void onVersionClick() {
        long now = clock.getAsLong();
        if (tapCount > 0 && now - lastTapAt > TAP_INTERVAL_MS) {
            tapCount = 0;
        }
        tapCount++;
        lastTapAt = now;
        if (tapCount >= TAPS_TO_OPEN) {
            tapCount = 0;
            event.setValue(AboutEvent.SHOW_SERVER_DIALOG);
        } else if (tapCount >= TAPS_TO_OPEN - 2) {
            showToast("再点 " + (TAPS_TO_OPEN - tapCount) + " 次打开服务器设置");
        }
    }

    /**
     * 对话框「保存」：地址格式不正确返回 INVALID；与当前地址相同返回 UNCHANGED；
     * 否则保存新地址、清除本机登录态（与退出登录相同）并返回 CHANGED。
     */
    @NonNull
    public ServerResult saveServer(@Nullable String input) {
        String normalized = ServerAddress.normalize(input);
        if (normalized == null) {
            return ServerResult.INVALID;
        }
        if (normalized.equals(serverAddress.current())) {
            return ServerResult.UNCHANGED;
        }
        serverAddress.set(normalized);
        onServerChanged();
        return ServerResult.CHANGED;
    }

    /** 对话框「恢复默认」：正在使用自定义地址时恢复默认并清除本机登录态（CHANGED），否则 UNCHANGED */
    @NonNull
    public ServerResult resetServer() {
        if (!serverAddress.isCustom()) {
            return ServerResult.UNCHANGED;
        }
        serverAddress.reset();
        onServerChanged();
        return ServerResult.CHANGED;
    }

    /** 换了服务器，原令牌与缓存的用户资料都属于旧服务器：清除 token、user_json、is_guest */
    private void onServerChanged() {
        userService.logout();
        refreshServer();
    }

    private void refreshServer() {
        boolean custom = serverAddress.isCustom();
        customServer.setValue(custom);
        serverText.setValue(custom ? SERVER_PREFIX + serverAddress.current() : "");
    }

    /** 对话框预填的当前地址 */
    @NonNull
    public String getCurrentServer() {
        return serverAddress.current();
    }

    /** 对话框辅助文字中的默认地址 */
    @NonNull
    public String getDefaultServer() {
        return serverAddress.getDefault();
    }

    public boolean isCustomServer() {
        return serverAddress.isCustom();
    }

    public String getVersionText() {
        return versionText;
    }

    public LiveData<String> getServerText() {
        return serverText;
    }

    public LiveData<Boolean> getCustomServer() {
        return customServer;
    }

    public LiveData<AppVersionVO> getLatestVersion() {
        return latestVersion;
    }

    public LiveData<AboutEvent> getEvent() {
        return event;
    }

    @VisibleForTesting
    void setClock(@NonNull LongSupplier clock) {
        this.clock = clock;
    }
}
