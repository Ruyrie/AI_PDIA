package com.heban.pestai.mine.ui.settings;

import static com.heban.pestai.mine.testutil.LiveDataTestUtil.record;
import static com.heban.pestai.mine.testutil.LiveDataTestUtil.value;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.mine.data.AppVersion;
import com.heban.pestai.mine.testutil.Fakes;
import com.heban.pestai.mine.testutil.Fakes.FakeCacheRepository;
import com.heban.pestai.mine.testutil.Fakes.FakeMineRepository;
import com.heban.pestai.mine.testutil.Fakes.FakeSettingsRepository;
import com.heban.pestai.mine.testutil.Fakes.FakeUserService;
import com.heban.pestai.mine.testutil.RxTestRule;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;

public class SettingsViewModelTest {

    private static final long BYTES_128_MB = 134217728L;

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rxRule = new RxTestRule();

    private FakeMineRepository mineRepository;
    private FakeCacheRepository cacheRepository;
    private FakeSettingsRepository settingsRepository;
    private FakeUserService userService;

    @Before
    public void setUp() {
        mineRepository = new FakeMineRepository();
        cacheRepository = new FakeCacheRepository();
        cacheRepository.sizeResult = Single.just(BYTES_128_MB);
        settingsRepository = new FakeSettingsRepository();
        userService = new FakeUserService();
        userService.loggedIn = true;
        userService.user = Fakes.sampleUser();
    }

    private SettingsViewModel create() {
        return new SettingsViewModel(mineRepository, cacheRepository, settingsRepository, userService,
                new AppVersion("1.0.0", 1));
    }

    // ---------- 账号 ----------

    @Test
    public void loggedIn_phoneMaskedAndChangePasswordCarriesFullPhone() {
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        assertFalse(value(vm.getGuest()));
        assertEquals("138****5678 · 用于登录和找回密码", value(vm.getPhoneText()));
        assertEquals("版本 1.0.0 · 用户协议 · 隐私政策", vm.getVersionText());

        vm.onChangePasswordClick();
        assertEquals(SettingsEvent.Type.GO_CHANGE_PWD, events.get(0).type);
        assertEquals("13800005678", vm.getFullPhone());
    }

    @Test
    public void guest_hidesAccountActions() {
        userService.loggedIn = false;
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        assertTrue(value(vm.getGuest()));
        assertEquals("未登录", value(vm.getPhoneText()));
        assertNull(vm.getFullPhone());

        vm.onChangePasswordClick();
        vm.onLogoutClick();
        vm.onPhoneClick();
        vm.onDeactivateClick();
        assertTrue(events.isEmpty());

        // 游客的「登录 / 注册」与切换账号同样进入 A03
        vm.onSwitchAccountClick();
        assertEquals(SettingsEvent.Type.GO_SWITCH_ACCOUNT, events.get(0).type);
    }

    @Test
    public void phoneRow_opensChangePhone() {
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        vm.onPhoneClick();
        assertEquals(1, events.size());
        assertEquals(SettingsEvent.Type.GO_CHANGE_PHONE, events.get(0).type);
    }

    @Test
    public void deactivateRow_opensDeactivate() {
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        vm.onDeactivateClick();
        assertEquals(1, events.size());
        assertEquals(SettingsEvent.Type.GO_DEACTIVATE, events.get(0).type);
        // 只是进入 A11，不清除登录态
        assertEquals(0, userService.logoutCalls);
    }

    @Test
    public void userChanged_afterChangePhone_refreshesPhone() {
        SettingsViewModel vm = create();
        assertEquals("138****5678 · 用于登录和找回密码", value(vm.getPhoneText()));

        UserVO changed = Fakes.sampleUser();
        changed.setPhone("13900001111");
        userService.user = changed;
        vm.onUserChanged(changed);

        assertEquals("139****1111 · 用于登录和找回密码", value(vm.getPhoneText()));
        // 修改密码带入新号码
        assertEquals("13900001111", vm.getFullPhone());
    }

    @Test
    public void userChanged_nullOrGuest_ignored() {
        SettingsViewModel vm = create();
        vm.onUserChanged(null);   // 退出登录 / 注销：页面随即跳转，不闪现空号码
        assertEquals("138****5678 · 用于登录和找回密码", value(vm.getPhoneText()));

        userService.loggedIn = false;
        SettingsViewModel guestVm = create();
        UserVO other = Fakes.sampleUser();
        other.setPhone("13900001111");
        guestVm.onUserChanged(other);
        assertEquals("未登录", value(guestVm.getPhoneText()));
        assertNull(guestVm.getFullPhone());
    }

    // ---------- 账号恢复码 ----------

    private static RecoveryCodeVO recovery(boolean generated, LocalDate created) {
        RecoveryCodeVO vo = new RecoveryCodeVO();
        vo.setGenerated(generated);
        if (created != null) {
            vo.setCreatedAt(Date.from(created.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        return vo;
    }

    @Test
    public void recoveryCode_unknownBeforeRefresh() {
        SettingsViewModel vm = create();
        assertEquals(0, mineRepository.recoveryCodeCalls);   // 由页面 onResume 触发
        assertEquals("手机号停用又忘记密码时用于找回账号", value(vm.getRecoveryCodeText()));
        assertFalse(value(vm.getRecoveryCodeMissing()));
    }

    @Test
    public void recoveryCode_notGenerated_showsHintAndDot() {
        mineRepository.recoveryCodeResult = Single.just(recovery(false, null));
        SettingsViewModel vm = create();
        vm.refreshRecoveryCode();
        assertEquals(1, mineRepository.recoveryCodeCalls);
        assertEquals("未生成 · 手机号停用又忘记密码时用于找回账号", value(vm.getRecoveryCodeText()));
        assertTrue(value(vm.getRecoveryCodeMissing()));
    }

    @Test
    public void recoveryCode_onResumeRefresh_showsGeneratedDate() {
        mineRepository.recoveryCodeResult = Single.just(recovery(false, null));
        SettingsViewModel vm = create();
        vm.setToday(() -> LocalDate.of(2026, 10, 13));
        vm.refreshRecoveryCode();
        assertTrue(value(vm.getRecoveryCodeMissing()));

        // 从 A12 生成后返回：onResume 再次查询
        mineRepository.recoveryCodeResult = Single.just(recovery(true, LocalDate.of(2026, 10, 13)));
        vm.refreshRecoveryCode();
        assertEquals(2, mineRepository.recoveryCodeCalls);
        assertEquals("已生成 · 10月13日", value(vm.getRecoveryCodeText()));
        assertFalse(value(vm.getRecoveryCodeMissing()));
    }

    @Test
    public void recoveryCode_failureKeepsPreviousStatus() {
        mineRepository.recoveryCodeResult = Single.just(recovery(false, null));
        SettingsViewModel vm = create();
        List<String> toasts = record(vm.getToast());
        vm.refreshRecoveryCode();

        mineRepository.recoveryCodeResult = Single.error(ApiException.of(ApiException.NETWORK));
        vm.refreshRecoveryCode();
        assertEquals("未生成 · 手机号停用又忘记密码时用于找回账号", value(vm.getRecoveryCodeText()));
        assertTrue(toasts.isEmpty());   // 静默失败
    }

    @Test
    public void recoveryCode_inFlightRequestNotRepeated() {
        SingleSubject<RecoveryCodeVO> pending = SingleSubject.create();
        mineRepository.recoveryCodeResult = pending;
        SettingsViewModel vm = create();
        vm.refreshRecoveryCode();
        vm.refreshRecoveryCode();
        assertEquals(1, mineRepository.recoveryCodeCalls);

        pending.onSuccess(recovery(true, null));
        assertEquals("已生成", value(vm.getRecoveryCodeText()));
        mineRepository.recoveryCodeResult = Single.just(recovery(true, null));
        vm.refreshRecoveryCode();
        assertEquals(2, mineRepository.recoveryCodeCalls);
    }

    @Test
    public void recoveryCode_rowOpensA12_guestIgnored() {
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());
        vm.onRecoveryCodeClick();
        assertEquals(1, events.size());
        assertEquals(SettingsEvent.Type.GO_RECOVERY_CODE, events.get(0).type);

        userService.loggedIn = false;
        SettingsViewModel guestVm = create();
        List<SettingsEvent> guestEvents = record(guestVm.getEvent());
        guestVm.onRecoveryCodeClick();
        guestVm.refreshRecoveryCode();
        assertTrue(guestEvents.isEmpty());
        assertEquals(0, mineRepository.recoveryCodeCalls);
    }

    // ---------- 保存到相册开关 ----------

    @Test
    public void saveToAlbum_initialValueFromSettings() {
        settingsRepository.saveToAlbum = true;
        assertTrue(value(create().getSaveToAlbum()));

        settingsRepository.saveToAlbum = false;
        assertFalse(value(create().getSaveToAlbum()));
    }

    @Test
    public void saveToAlbum_writesOnlyWhenChanged() {
        SettingsViewModel vm = create();
        value(vm.getSaveToAlbum());

        vm.getSaveToAlbum().setValue(true);
        assertEquals(1, settingsRepository.writes);
        assertTrue(settingsRepository.saveToAlbum);

        // 双向绑定回写相同的值：不重复写入
        vm.getSaveToAlbum().setValue(true);
        assertEquals(1, settingsRepository.writes);

        vm.getSaveToAlbum().setValue(false);
        assertEquals(2, settingsRepository.writes);
        assertFalse(settingsRepository.saveToAlbum);
        assertFalse(value(vm.getSaveToAlbum()));
    }

    // ---------- 清理图片缓存 ----------

    @Test
    public void cacheSize_computingThenFormatted() {
        cacheRepository.sizeResult = Single.never();
        SettingsViewModel vm = create();
        assertEquals("计算中… · 图片框架磁盘缓存", value(vm.getCacheSizeText()));
        assertFalse(value(vm.getCanClear()));

        cacheRepository.sizeResult = Single.just(BYTES_128_MB);
        vm = create();
        assertEquals("128 MB · 图片框架磁盘缓存", value(vm.getCacheSizeText()));
        assertTrue(value(vm.getCanClear()));
    }

    @Test
    public void cacheSize_emptyCannotClear() {
        cacheRepository.sizeResult = Single.just(0L);
        SettingsViewModel vm = create();
        assertEquals("0 B · 图片框架磁盘缓存", value(vm.getCacheSizeText()));
        assertFalse(value(vm.getCanClear()));

        vm.clearCache();
        assertEquals(0, cacheRepository.clearCalls);
    }

    @Test
    public void clearCache_success() {
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());
        value(vm.getCanClear());

        vm.clearCache();

        assertEquals(1, cacheRepository.clearCalls);
        assertEquals("0 B · 图片框架磁盘缓存", value(vm.getCacheSizeText()));
        assertFalse(value(vm.getClearing()));
        assertFalse(value(vm.getCanClear()));
        assertEquals(1, events.size());
        assertEquals(SettingsEvent.Type.CACHE_CLEARED, events.get(0).type);
        assertEquals("已清理 128 MB 图片缓存", events.get(0).message);

        // 已为 0 B，再点不再清理
        vm.clearCache();
        assertEquals(1, cacheRepository.clearCalls);
    }

    @Test
    public void clearCache_failureKeepsSize() {
        cacheRepository.clearResult = Completable.error(new IOException("disk"));
        SettingsViewModel vm = create();
        List<String> toasts = record(vm.getToast());

        vm.clearCache();

        assertEquals(Collections.singletonList("清理失败，请稍后重试"), toasts);
        assertEquals(Long.valueOf(BYTES_128_MB), value(vm.getCacheBytes()));
        assertFalse(value(vm.getClearing()));
        assertTrue(value(vm.getCanClear()));
    }

    @Test
    public void clearCache_inProgressIgnoresRepeatedClicks() {
        CompletableSubject clear = CompletableSubject.create();
        cacheRepository.clearResult = clear;
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        vm.clearCache();
        assertTrue(value(vm.getClearing()));
        assertFalse(value(vm.getCanClear()));
        vm.clearCache();
        assertEquals(1, cacheRepository.clearCalls);

        clear.onComplete();
        assertFalse(value(vm.getClearing()));
        assertEquals(1, events.size());
    }

    // ---------- 退出登录 ----------

    @Test
    public void logout_showsDialogThenClearsSession() {
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        vm.onLogoutClick();
        assertEquals(SettingsEvent.Type.SHOW_LOGOUT_DIALOG, events.get(0).type);
        assertEquals(0, mineRepository.logoutCalls);

        vm.confirmLogout();
        assertEquals(1, mineRepository.logoutCalls);
        assertEquals(1, userService.logoutCalls);
        assertEquals(SettingsEvent.Type.LOGGED_OUT, events.get(1).type);
        assertFalse(value(vm.getLoggingOut()));
    }

    @Test
    public void logout_networkFailureStillLogsOut() {
        mineRepository.logoutResult = Completable.error(ApiException.of(ApiException.NETWORK));
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        vm.confirmLogout();

        assertEquals(1, userService.logoutCalls);
        assertEquals(SettingsEvent.Type.LOGGED_OUT, events.get(0).type);
    }

    @Test
    public void logout_tokenExpiredStillLogsOut() {
        mineRepository.logoutResult = Completable.error(ApiException.of(ApiException.UNAUTHORIZED));
        SettingsViewModel vm = create();
        vm.confirmLogout();
        assertEquals(1, userService.logoutCalls);
    }

    @Test
    public void logout_timeoutAfterFiveSecondsStillLogsOut() {
        mineRepository.logoutResult = Completable.never();
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        vm.confirmLogout();
        assertTrue(value(vm.getLoggingOut()));
        assertEquals(0, userService.logoutCalls);

        // 请求中重复点击不会再次提交
        vm.confirmLogout();
        assertEquals(1, mineRepository.logoutCalls);

        rxRule.computation.advanceTimeBy(SettingsViewModel.LOGOUT_TIMEOUT_SECONDS - 1, TimeUnit.SECONDS);
        assertEquals(0, userService.logoutCalls);

        rxRule.computation.advanceTimeBy(1, TimeUnit.SECONDS);
        assertEquals(1, userService.logoutCalls);
        assertEquals(SettingsEvent.Type.LOGGED_OUT, events.get(0).type);
        assertFalse(value(vm.getLoggingOut()));
    }

    // ---------- 其他入口 ----------

    @Test
    public void aboutAndSwitchAccount() {
        SettingsViewModel vm = create();
        List<SettingsEvent> events = record(vm.getEvent());

        vm.onAboutClick();
        vm.onSwitchAccountClick();

        assertEquals(SettingsEvent.Type.GO_ABOUT, events.get(0).type);
        assertEquals(SettingsEvent.Type.GO_SWITCH_ACCOUNT, events.get(1).type);
        // 切换账号不清除登录态
        assertEquals(0, userService.logoutCalls);
        assertEquals(0, mineRepository.logoutCalls);
    }
}
