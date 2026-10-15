package com.heban.pestai.mine.ui;

import static com.heban.pestai.mine.testutil.LiveDataTestUtil.record;
import static com.heban.pestai.mine.testutil.LiveDataTestUtil.value;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.base.UiState;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.mine.data.AppVersion;
import com.heban.pestai.mine.testutil.Fakes;
import com.heban.pestai.mine.testutil.Fakes.FakeMineRepository;
import com.heban.pestai.mine.testutil.Fakes.FakeReminderStore;
import com.heban.pestai.mine.testutil.Fakes.FakeUserService;
import com.heban.pestai.mine.testutil.RxTestRule;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Single;

public class MineViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rxRule = new RxTestRule();

    /** 2026-10-13 09:00（毫秒），提醒关闭时间的基准 */
    private static final long NOW = 1_791_853_200_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private FakeMineRepository repository;
    private FakeUserService userService;
    private FakeReminderStore reminderStore;
    private long now = NOW;

    @Before
    public void setUp() {
        repository = new FakeMineRepository();
        userService = new FakeUserService();
        reminderStore = new FakeReminderStore();
        userService.loggedIn = true;
        userService.user = Fakes.sampleUser();
        repository.meResult = Single.just(Fakes.sampleUser());
        repository.statsResult = Single.just(Fakes.sampleStats(null));
    }

    private MineViewModel create() {
        MineViewModel vm = new MineViewModel(repository, userService, new AppVersion("1.0.0", 1), reminderStore);
        vm.setClock(() -> now);
        return vm;
    }

    @Test
    public void guest_showsPlaceholdersWithoutRequest() {
        userService.loggedIn = false;
        MineViewModel vm = create();
        vm.refreshIfNeeded();

        assertTrue(value(vm.getGuest()));
        assertTrue(value(vm.getPersonAvatar()));
        assertEquals("--", value(vm.getTotalText()));
        assertEquals("--", value(vm.getPestKindsText()));
        assertEquals("--", value(vm.getAiCountText()));
        assertEquals("", value(vm.getRecordCountText()));
        assertEquals("本月已识别 -- 次", value(vm.getMonthText()));
        assertEquals(0, repository.meCalls);
        assertEquals(0, repository.statsCalls);
    }

    @Test
    public void guest_loginRequiredEntriesAskToLogin() {
        userService.loggedIn = false;
        MineViewModel vm = create();
        List<MineEvent> events = record(vm.getEvent());

        vm.onScanClick();
        vm.onHistoryClick();
        vm.onStatClick(0);
        vm.onStatClick(1);
        vm.onStatClick(2);
        vm.onAskAiClick();
        vm.onLoginClick();

        assertEquals(Arrays.asList(MineEvent.NEED_LOGIN, MineEvent.NEED_LOGIN, MineEvent.NEED_LOGIN,
                MineEvent.NEED_LOGIN, MineEvent.GO_AI_TAB, MineEvent.GO_AI_TAB, MineEvent.GO_LOGIN), events);
    }

    @Test
    public void cachedProfileShownBeforeRequest() {
        repository.meResult = Single.never();
        repository.statsResult = Single.never();
        MineViewModel vm = create();

        assertFalse(value(vm.getGuest()));
        assertEquals("老李种菜", value(vm.getNameText()));
        assertEquals("138****5678 · 江苏 南京", value(vm.getPhoneRegion()));
        assertEquals("--", value(vm.getTotalText()));

        vm.refreshIfNeeded();
        assertEquals(UiState.LOADING, value(vm.getUiState()));
        assertEquals("--", value(vm.getTotalText()));
    }

    @Test
    public void loggedIn_loadsProfileAndStatsInParallel() {
        MineViewModel vm = create();
        vm.refreshIfNeeded();

        assertEquals(1, repository.meCalls);
        assertEquals(1, repository.statsCalls);
        assertEquals(UiState.SUCCESS, value(vm.getUiState()));
        assertEquals("老李种菜", value(vm.getNameText()));
        assertEquals("老", value(vm.getAvatarText()));
        assertFalse(value(vm.getPersonAvatar()));
        assertEquals("138****5678 · 江苏 南京", value(vm.getPhoneRegion()));
        assertEquals("36", value(vm.getTotalText()));
        assertEquals("12", value(vm.getPestKindsText()));
        assertEquals("18", value(vm.getAiCountText()));
        assertEquals("36 条", value(vm.getRecordCountText()));
        assertEquals("本月已识别 9 次", value(vm.getMonthText()));
        assertEquals("最近一次：暂无", value(vm.getLastRecordText()));
        assertEquals("v1.0.0", vm.getVersionText());
        assertFalse(vm.isDirty());
    }

    @Test
    public void profileRequestFails_fallsBackToCache() {
        repository.meResult = Single.error(ApiException.of(ApiException.NETWORK));
        MineViewModel vm = create();
        vm.refreshIfNeeded();

        assertEquals(UiState.SUCCESS, value(vm.getUiState()));
        assertEquals(Fakes.sampleUser(), value(vm.getUser()));
        assertEquals("36", value(vm.getTotalText()));
    }

    @Test
    public void profileUpdatedFromServer() {
        UserVO server = Fakes.sampleUser();
        server.setNickname("李大哥");
        server.setRegion("山东省 寿光市");
        repository.meResult = Single.just(server);
        MineViewModel vm = create();
        vm.refreshIfNeeded();

        assertEquals("李大哥", value(vm.getNameText()));
        assertEquals("138****5678 · 山东 寿光", value(vm.getPhoneRegion()));
    }

    @Test
    public void firstStatsFailure_keepsPlaceholderAndShowsToast() {
        repository.statsResult = Single.error(ApiException.of(ApiException.NETWORK));
        MineViewModel vm = create();
        List<String> toasts = record(vm.getToast());
        vm.refreshIfNeeded();

        assertEquals(UiState.ERROR, value(vm.getUiState()));
        assertEquals("--", value(vm.getTotalText()));
        assertEquals(Arrays.asList(ApiException.defaultMessage(ApiException.NETWORK)), toasts);
        assertTrue(vm.isDirty());

        // 下次可见时重试
        repository.statsResult = Single.just(Fakes.sampleStats(null));
        vm.refreshIfNeeded();
        assertEquals(2, repository.statsCalls);
        assertEquals("36", value(vm.getTotalText()));
        assertEquals(UiState.SUCCESS, value(vm.getUiState()));
    }

    @Test
    public void laterStatsFailure_keepsPreviousStats() {
        MineViewModel vm = create();
        vm.refreshIfNeeded();
        assertEquals("36", value(vm.getTotalText()));

        repository.statsResult = Single.error(ApiException.of(ApiException.TIMEOUT));
        vm.markDirty();
        vm.refreshIfNeeded();

        assertEquals(UiState.ERROR, value(vm.getUiState()));
        assertEquals("36", value(vm.getTotalText()));
        assertEquals("36 条", value(vm.getRecordCountText()));
    }

    @Test
    public void refreshIfNeeded_onlyWhenDirty() {
        MineViewModel vm = create();
        vm.refreshIfNeeded();
        vm.refreshIfNeeded();
        vm.refreshIfNeeded();
        assertEquals(1, repository.statsCalls);

        // 识别或删除记录：只标记，可见时合并为一次刷新
        vm.markDirty();
        vm.markDirty();
        vm.markDirty();
        assertEquals(1, repository.statsCalls);
        vm.refreshIfNeeded();
        assertEquals(2, repository.statsCalls);
        vm.refreshIfNeeded();
        assertEquals(2, repository.statsCalls);
    }

    @Test
    public void refreshWhileLoading_doesNotRequestAgain() {
        repository.statsResult = Single.never();
        MineViewModel vm = create();
        vm.refreshIfNeeded();
        vm.refresh();
        assertEquals(1, repository.statsCalls);
    }

    @Test
    public void loginStateChange_triggersRefresh() {
        userService.loggedIn = false;
        MineViewModel vm = create();
        vm.refreshIfNeeded();
        assertEquals(0, repository.statsCalls);

        userService.loggedIn = true;
        vm.refreshIfNeeded();
        assertEquals(1, repository.statsCalls);
        assertFalse(value(vm.getGuest()));
        assertEquals("36", value(vm.getTotalText()));
    }

    @Test
    public void userChanges_sameUserIgnored_newUserMarksDirty() {
        MineViewModel vm = create();
        vm.refreshIfNeeded();

        vm.onUserChanged(Fakes.sampleUser());
        assertFalse(vm.isDirty());
        vm.onUserChanged(null);
        assertFalse(vm.isDirty());

        UserVO edited = Fakes.sampleUser();
        edited.setNickname("老李");
        vm.onUserChanged(edited);
        assertTrue(vm.isDirty());
        assertSame(edited, value(vm.getUser()));
        assertEquals("老李", value(vm.getNameText()));
    }

    @Test
    public void loggedIn_clicksNavigate() {
        MineViewModel vm = create();
        List<MineEvent> events = record(vm.getEvent());

        vm.onStatClick(0);
        vm.onStatClick(1);
        vm.onStatClick(2);
        vm.onScanClick();
        vm.onHistoryClick();
        vm.onSettingsClick();
        vm.onAboutClick();

        assertEquals(Arrays.asList(MineEvent.GO_HISTORY, MineEvent.GO_HISTORY_PEST, MineEvent.GO_AI_TAB,
                MineEvent.GO_SCAN, MineEvent.GO_HISTORY, MineEvent.GO_SETTINGS, MineEvent.GO_ABOUT), events);
    }

    @Test
    public void profileClick_loggedInOpensEditProfile() {
        MineViewModel vm = create();
        List<MineEvent> events = record(vm.getEvent());

        vm.onProfileClick();

        assertEquals(Arrays.asList(MineEvent.GO_EDIT_PROFILE), events);
    }

    @Test
    public void profileClick_guestOpensLogin() {
        userService.loggedIn = false;
        MineViewModel vm = create();
        List<MineEvent> events = record(vm.getEvent());

        vm.onProfileClick();

        assertEquals(Arrays.asList(MineEvent.GO_LOGIN), events);
    }

    @Test
    public void editProfileSaved_userChangesUpdateNameAndAvatar() {
        MineViewModel vm = create();
        vm.refreshIfNeeded();

        // A06 编辑资料保存后 UserSession.saveUser() 经 userChanges() 发出新资料
        UserVO saved = Fakes.sampleUser();
        saved.setNickname("老李种番茄");
        saved.setAvatarUrl("http://10.0.2.2:8080/files/avatar/20261014/new.jpg");
        vm.onUserChanged(saved);

        assertEquals("老李种番茄", value(vm.getNameText()));
        assertEquals("老", value(vm.getAvatarText()));
        assertEquals(saved.getAvatarUrl(), value(vm.getUser()).getAvatarUrl());
        assertFalse(value(vm.getPersonAvatar()));
        assertTrue(vm.isDirty());   // 下次可见时再以 user/me 为准
    }

    // ---------- 未生成账号恢复码提醒 ----------

    @Test
    public void reminder_notGenerated_shows() {
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(false));
        MineViewModel vm = create();
        assertFalse(value(vm.getShowRecoveryReminder()));   // 查询前不显示

        vm.refreshRecoveryReminder();
        assertEquals(1, repository.recoveryCodeCalls);
        assertTrue(value(vm.getShowRecoveryReminder()));
    }

    @Test
    public void reminder_generated_hidden() {
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(true));
        MineViewModel vm = create();
        vm.refreshRecoveryReminder();
        assertFalse(value(vm.getShowRecoveryReminder()));
    }

    @Test
    public void reminder_queryFailure_hidden() {
        repository.recoveryCodeResult = Single.error(ApiException.of(ApiException.NETWORK));
        MineViewModel vm = create();
        List<String> toasts = record(vm.getToast());
        vm.refreshRecoveryReminder();
        assertFalse(value(vm.getShowRecoveryReminder()));
        assertTrue(toasts.isEmpty());   // 不打扰用户
    }

    @Test
    public void reminder_guest_hiddenWithoutRequest() {
        userService.loggedIn = false;
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(false));
        MineViewModel vm = create();
        vm.refreshRecoveryReminder();
        assertFalse(value(vm.getShowRecoveryReminder()));
        assertEquals(0, repository.recoveryCodeCalls);
    }

    @Test
    public void reminder_hiddenAfterLogout() {
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(false));
        MineViewModel vm = create();
        vm.refreshRecoveryReminder();
        assertTrue(value(vm.getShowRecoveryReminder()));

        userService.logout();
        vm.refreshIfNeeded();
        assertFalse(value(vm.getShowRecoveryReminder()));
        vm.refreshRecoveryReminder();
        assertFalse(value(vm.getShowRecoveryReminder()));
        assertEquals(1, repository.recoveryCodeCalls);
    }

    @Test
    public void reminder_disappearsOnResumeAfterGenerating() {
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(false));
        MineViewModel vm = create();
        vm.refreshRecoveryReminder();
        assertTrue(value(vm.getShowRecoveryReminder()));

        // 「去生成」→ A12 生成后返回：onResume 再次查询
        List<MineEvent> events = record(vm.getEvent());
        vm.onRecoveryReminderClick();
        assertEquals(Arrays.asList(MineEvent.GO_RECOVERY_CODE), events);
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(true));
        vm.refreshRecoveryReminder();
        assertEquals(2, repository.recoveryCodeCalls);
        assertFalse(value(vm.getShowRecoveryReminder()));
    }

    @Test
    public void reminder_dismissed_hiddenForSevenDaysPerUser() {
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(false));
        MineViewModel vm = create();
        vm.refreshRecoveryReminder();
        assertTrue(value(vm.getShowRecoveryReminder()));

        vm.dismissRecoveryReminder();
        assertFalse(value(vm.getShowRecoveryReminder()));
        assertEquals(Long.valueOf(NOW), reminderStore.dismissed.get(10001L));   // 按用户 ID 记录

        // 6 天 23 小时后仍不显示，也不查询
        now = NOW + 7 * DAY - TimeUnit.HOURS.toMillis(1);
        vm.refreshRecoveryReminder();
        assertFalse(value(vm.getShowRecoveryReminder()));
        assertEquals(1, repository.recoveryCodeCalls);

        // 满 7 天后重新查询并显示
        now = NOW + 7 * DAY;
        vm.refreshRecoveryReminder();
        assertEquals(2, repository.recoveryCodeCalls);
        assertTrue(value(vm.getShowRecoveryReminder()));
    }

    @Test
    public void reminder_dismissedByAnotherUser_stillShows() {
        reminderStore.dismissed.put(20002L, NOW - DAY);
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(false));
        MineViewModel vm = create();
        vm.refreshRecoveryReminder();
        assertTrue(value(vm.getShowRecoveryReminder()));
    }

    @Test
    public void reminder_switchAccount_hidesOldResultFirst() {
        repository.recoveryCodeResult = Single.just(Fakes.recoveryStatus(false));
        MineViewModel vm = create();
        vm.refreshRecoveryReminder();
        assertTrue(value(vm.getShowRecoveryReminder()));

        UserVO other = Fakes.sampleUser();
        other.setId(20002L);
        userService.user = other;
        repository.recoveryCodeResult = Single.never();
        vm.refreshRecoveryReminder();
        assertFalse(value(vm.getShowRecoveryReminder()));   // 新账号的状态未知前不显示
        assertEquals(2, repository.recoveryCodeCalls);
    }

    @Test
    public void reminder_requestInFlight_notRepeated() {
        repository.recoveryCodeResult = Single.never();
        MineViewModel vm = create();
        vm.refreshRecoveryReminder();
        vm.refreshRecoveryReminder();
        assertEquals(1, repository.recoveryCodeCalls);
    }

    @Test
    public void reminder_clickAsGuest_asksToLogin() {
        userService.loggedIn = false;
        MineViewModel vm = create();
        List<MineEvent> events = record(vm.getEvent());
        vm.onRecoveryReminderClick();
        assertEquals(Arrays.asList(MineEvent.NEED_LOGIN), events);
    }

    @Test
    public void loggedInWithoutCache_profileFailureFailsWhole() {
        userService.user = null;
        repository.meResult = Single.error(ApiException.of(ApiException.NETWORK));
        MineViewModel vm = create();
        vm.refreshIfNeeded();

        assertEquals(UiState.ERROR, value(vm.getUiState()));
        assertNull(value(vm.getUser()));
        assertTrue(value(vm.getPersonAvatar()));
    }
}
