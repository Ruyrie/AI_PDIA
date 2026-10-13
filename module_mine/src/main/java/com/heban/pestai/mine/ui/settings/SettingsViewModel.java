package com.heban.pestai.mine.ui.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.mine.data.AppVersion;
import com.heban.pestai.mine.data.CacheRepository;
import com.heban.pestai.mine.data.MineRepository;
import com.heban.pestai.mine.data.SettingsRepository;
import com.heban.pestai.mine.format.RecoveryCodeFormatter;
import com.heban.pestai.mine.format.SizeFormatter;
import com.heban.pestai.mine.format.UserFormatter;

import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * M02 设置（模块09 技术准备文档 2.4「SettingsViewModel」、2.7.3–2.7.7）。
 * <p>新增：「手机号」行进入 A10 更换手机号、「注销账号」进入 A11（游客不响应）；
 * 页面把 IUserService.userChanges() 转给 {@link #onUserChanged(UserVO)}，从 A10 返回后手机号自动刷新。</p>
 * <p>新增：「账号恢复码」行进入 A12；页面 onResume 时调用 {@link #refreshRecoveryCode()}（GET user/recovery-code），
 * 副标题显示「未生成 · …」（error 色小圆点）或「已生成 · 10月13日」。</p>
 */
public class SettingsViewModel extends BaseViewModel {

    /** auth/logout 超时：超时或失败都继续清除本地登录态 */
    static final long LOGOUT_TIMEOUT_SECONDS = 5;

    static final String CACHE_SUFFIX = " · 图片框架磁盘缓存";
    static final String PHONE_SUFFIX = " · 用于登录和找回密码";

    private final MineRepository mineRepository;
    private final CacheRepository cacheRepository;
    private final SettingsRepository settingsRepository;
    private final IUserService userService;

    private final MutableLiveData<UserVO> user = new MutableLiveData<UserVO>(null);
    private final MutableLiveData<Boolean> guest = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> saveToAlbum;
    /** 磁盘缓存字节数，null 表示计算中（初值显式为 null，未赋值的 LiveData 不会触发 map()） */
    private final MutableLiveData<Long> cacheBytes = new MutableLiveData<Long>(null);
    private final MutableLiveData<Boolean> clearing = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canClear = new MediatorLiveData<>();
    private final MutableLiveData<Boolean> loggingOut = new MutableLiveData<>(false);
    /** 账号恢复码状态；null 表示尚未查询到（查询失败时保留上一次的结果） */
    private final MutableLiveData<RecoveryCodeVO> recoveryCode = new MutableLiveData<RecoveryCodeVO>(null);
    private final LiveData<String> recoveryCodeText;
    private final LiveData<Boolean> recoveryCodeMissing;
    private final SingleLiveEvent<SettingsEvent> event = new SingleLiveEvent<>();

    private final LiveData<String> phoneText;
    private final LiveData<String> cacheSizeText;
    private final String versionText;

    private Supplier<LocalDate> today = LocalDate::now;
    @Nullable
    private Disposable recoveryRequest;

    @Inject
    public SettingsViewModel(MineRepository mineRepository, CacheRepository cacheRepository,
                             SettingsRepository settingsRepository, IUserService userService,
                             AppVersion appVersion) {
        this.mineRepository = mineRepository;
        this.cacheRepository = cacheRepository;
        this.settingsRepository = settingsRepository;
        this.userService = userService;
        this.versionText = "版本 " + appVersion.name() + " · 用户协议 · 隐私政策";

        boolean loggedIn = userService.isLoggedIn();
        guest.setValue(!loggedIn);
        user.setValue(loggedIn ? userService.currentUser() : null);

        // 双向绑定的开关：值未变化时直接返回，打断「LiveData 更新开关、开关又回写」的循环，也避免重复写盘
        saveToAlbum = new MutableLiveData<Boolean>(settingsRepository.isSaveToAlbum()) {
            @Override
            public void setValue(Boolean value) {
                if (Objects.equals(value, getValue())) {
                    return;
                }
                super.setValue(value);
                SettingsViewModel.this.settingsRepository.setSaveToAlbum(Boolean.TRUE.equals(value));
            }
        };

        phoneText = Transformations.map(user, this::formatPhone);
        cacheSizeText = Transformations.map(cacheBytes,
                bytes -> (bytes == null ? "计算中…" : SizeFormatter.format(bytes)) + CACHE_SUFFIX);
        recoveryCodeText = Transformations.map(recoveryCode,
                vo -> RecoveryCodeFormatter.subtitle(vo, today.get()));
        recoveryCodeMissing = Transformations.map(recoveryCode, RecoveryCodeFormatter::isMissing);
        canClear.addSource(cacheBytes, b -> updateCanClear());
        canClear.addSource(clearing, c -> updateCanClear());

        // 在构造时计算，旋转屏幕不会重复计算
        loadCacheSize();
    }

    private String formatPhone(@Nullable UserVO u) {
        if (Boolean.TRUE.equals(guest.getValue())) {
            return "未登录";
        }
        String phone = u == null ? "" : UserFormatter.maskPhone(u.getPhone());
        return phone.isEmpty() ? "用于登录和找回密码" : phone + PHONE_SUFFIX;
    }

    private void updateCanClear() {
        Long bytes = cacheBytes.getValue();
        canClear.setValue(bytes != null && bytes > 0 && !Boolean.TRUE.equals(clearing.getValue()));
    }

    // ---------- 图片缓存 ----------

    /** io 线程递归计算 Glide 磁盘缓存目录大小 */
    public void loadCacheSize() {
        add(cacheRepository.diskCacheSize()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(cacheBytes::setValue, t -> cacheBytes.setValue(0L)));
    }

    /** clearDiskCache()（io）→ clearMemory()（主线程）→ 大小置 0 → 发 CACHE_CLEARED */
    public void clearCache() {
        Long bytes = cacheBytes.getValue();
        if (Boolean.TRUE.equals(clearing.getValue()) || bytes == null || bytes <= 0) {
            return;
        }
        final long before = bytes;
        add(cacheRepository.clear()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(d -> clearing.setValue(true))
                .doFinally(() -> clearing.setValue(false))
                .subscribe(() -> {
                    cacheBytes.setValue(0L);
                    event.setValue(SettingsEvent.cacheCleared("已清理 " + SizeFormatter.format(before) + " 图片缓存"));
                }, t -> showToast("清理失败，请稍后重试")));
    }

    // ---------- 账号 ----------

    /**
     * IUserService.userChanges() 发出新值（如 A10 换绑后保存了新的 user_json）：刷新手机号等资料。
     * 退出登录 / 注销时为 null，页面随即清栈跳转登录页，这里不处理，避免闪现空号码。
     */
    public void onUserChanged(@Nullable UserVO changed) {
        if (changed == null || Boolean.TRUE.equals(guest.getValue()) || !userService.isLoggedIn()
                || changed.equals(user.getValue())) {
            return;
        }
        user.setValue(changed);
    }

    /** 「手机号」行 → A10 更换手机号（游客不响应） */
    public void onPhoneClick() {
        if (!Boolean.TRUE.equals(guest.getValue())) {
            event.setValue(SettingsEvent.of(SettingsEvent.Type.GO_CHANGE_PHONE));
        }
    }

    /** 「注销账号」→ A11（游客不响应） */
    public void onDeactivateClick() {
        if (!Boolean.TRUE.equals(guest.getValue())) {
            event.setValue(SettingsEvent.of(SettingsEvent.Type.GO_DEACTIVATE));
        }
    }

    /** 「账号恢复码」行 → A12（游客不响应） */
    public void onRecoveryCodeClick() {
        if (!Boolean.TRUE.equals(guest.getValue())) {
            event.setValue(SettingsEvent.of(SettingsEvent.Type.GO_RECOVERY_CODE));
        }
    }

    /**
     * 页面 onResume 时刷新恢复码状态（从 A12 生成后返回即显示「已生成」）。游客不请求；上一次请求未结束时不重复发出；
     * 失败时保留原来的状态，不打扰用户。
     */
    public void refreshRecoveryCode() {
        if (Boolean.TRUE.equals(guest.getValue()) || !userService.isLoggedIn()) {
            return;
        }
        if (recoveryRequest != null && !recoveryRequest.isDisposed()) {
            return;
        }
        recoveryRequest = mineRepository.recoveryCode()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(recoveryCode::setValue, t -> {
                });
        add(recoveryRequest);
    }

    public void onChangePasswordClick() {
        if (getFullPhone() != null) {
            event.setValue(SettingsEvent.of(SettingsEvent.Type.GO_CHANGE_PWD));
        }
    }

    /** 修改密码时带入 A07 的完整（未脱敏）手机号；游客或没有资料时为 null */
    @Nullable
    public String getFullPhone() {
        UserVO u = user.getValue();
        if (Boolean.TRUE.equals(guest.getValue()) || u == null || UserFormatter.isBlank(u.getPhone())) {
            return null;
        }
        return u.getPhone().trim();
    }

    public void onAboutClick() {
        event.setValue(SettingsEvent.of(SettingsEvent.Type.GO_ABOUT));
    }

    /** 切换账号（游客为「登录 / 注册」）：普通打开 A03，不清除当前登录态 */
    public void onSwitchAccountClick() {
        event.setValue(SettingsEvent.of(SettingsEvent.Type.GO_SWITCH_ACCOUNT));
    }

    public void onLogoutClick() {
        if (!Boolean.TRUE.equals(guest.getValue())) {
            event.setValue(SettingsEvent.of(SettingsEvent.Type.SHOW_LOGOUT_DIALOG));
        }
    }

    /**
     * 确认退出：POST auth/logout（5 秒超时；网络失败、超时、40101 都当作完成）→ IUserService.logout() → LOGGED_OUT。
     * 保证没有网络也一定能退出。
     */
    public void confirmLogout() {
        if (Boolean.TRUE.equals(loggingOut.getValue())) {
            return;
        }
        add(mineRepository.logout()
                .timeout(LOGOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .onErrorComplete()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(d -> loggingOut.setValue(true))
                .doFinally(() -> loggingOut.setValue(false))
                .subscribe(() -> {
                    userService.logout();
                    event.setValue(SettingsEvent.of(SettingsEvent.Type.LOGGED_OUT));
                }));
    }

    // ---------- getter ----------

    public LiveData<UserVO> getUser() {
        return user;
    }

    public LiveData<Boolean> getGuest() {
        return guest;
    }

    public MutableLiveData<Boolean> getSaveToAlbum() {
        return saveToAlbum;
    }

    public LiveData<Long> getCacheBytes() {
        return cacheBytes;
    }

    public LiveData<String> getCacheSizeText() {
        return cacheSizeText;
    }

    public LiveData<Boolean> getClearing() {
        return clearing;
    }

    public LiveData<Boolean> getCanClear() {
        return canClear;
    }

    public LiveData<Boolean> getLoggingOut() {
        return loggingOut;
    }

    public LiveData<RecoveryCodeVO> getRecoveryCode() {
        return recoveryCode;
    }

    /** 「账号恢复码」行副标题 */
    public LiveData<String> getRecoveryCodeText() {
        return recoveryCodeText;
    }

    /** 已明确未生成：显示 error 色小圆点 */
    public LiveData<Boolean> getRecoveryCodeMissing() {
        return recoveryCodeMissing;
    }

    @VisibleForTesting
    void setToday(@NonNull Supplier<LocalDate> today) {
        this.today = today;
    }

    public LiveData<String> getPhoneText() {
        return phoneText;
    }

    public String getVersionText() {
        return versionText;
    }

    public LiveData<SettingsEvent> getEvent() {
        return event;
    }
}
