package com.heban.pestai.mine.ui.about;

import static com.heban.pestai.mine.testutil.LiveDataTestUtil.record;
import static com.heban.pestai.mine.testutil.LiveDataTestUtil.value;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.mine.data.AppVersion;
import com.heban.pestai.mine.testutil.Fakes;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AboutViewModelTest {

    private static final String DEFAULT_SERVER = "http://10.0.2.2:8080";
    private static final String HINT_2 = "再点 2 次打开服务器设置";
    private static final String HINT_1 = "再点 1 次打开服务器设置";

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private Fakes.FakeServerAddress server;
    private Fakes.FakeUserService userService;
    private long now = 10_000L;

    @Before
    public void setUp() {
        server = new Fakes.FakeServerAddress(DEFAULT_SERVER);
        userService = new Fakes.FakeUserService();
        userService.loggedIn = true;
        userService.user = Fakes.sampleUser();
    }

    private AboutViewModel create() {
        AboutViewModel vm = new AboutViewModel(new AppVersion("1.0.0", 1), server, userService);
        vm.setClock(() -> now);
        return vm;
    }

    /** 连续点击 times 次，每次间隔 gapMs */
    private void tap(AboutViewModel vm, int times, long gapMs) {
        for (int i = 0; i < times; i++) {
            now += gapMs;
            vm.onVersionClick();
        }
    }

    @Test
    public void versionTextFromInstalledPackage() {
        AboutViewModel vm = create();
        assertEquals("拍照识病虫 · 版本 1.0.0（1）", vm.getVersionText());
    }

    @Test
    public void checkUpdateOnlyShowsNotOpen() {
        AboutViewModel vm = create();
        List<AboutEvent> events = record(vm.getEvent());

        vm.onCheckUpdate();
        vm.onCheckUpdate();
        vm.onUserAgreementClick();
        vm.onPrivacyClick();

        assertEquals(Arrays.asList(AboutEvent.NOT_OPEN, AboutEvent.NOT_OPEN,
                AboutEvent.OPEN_USER_AGREEMENT, AboutEvent.OPEN_PRIVACY), events);
        // v1.0 不请求 app/version，最新版本始终为空
        assertNull(value(vm.getLatestVersion()));
    }

    // ---------- 连续点击版本号 ----------

    @Test
    public void fiveQuickTapsOpenServerDialog() {
        AboutViewModel vm = create();
        List<AboutEvent> events = record(vm.getEvent());
        List<String> toasts = record(vm.getToast());

        tap(vm, 2, 300);
        assertTrue(toasts.isEmpty());
        tap(vm, 2, 300);
        assertEquals(Arrays.asList(HINT_2, HINT_1), toasts);
        assertTrue(events.isEmpty());

        tap(vm, 1, 300);
        assertEquals(Collections.singletonList(AboutEvent.SHOW_SERVER_DIALOG), events);
        assertEquals(2, toasts.size());
    }

    @Test
    public void slowTapRestartsCount() {
        AboutViewModel vm = create();
        List<AboutEvent> events = record(vm.getEvent());
        List<String> toasts = record(vm.getToast());

        tap(vm, 3, 200);
        // 间隔超过 1.5 秒：这一下算第 1 次
        tap(vm, 1, 1501);
        tap(vm, 3, 200);
        assertTrue(events.isEmpty());
        assertEquals(Arrays.asList(HINT_2, HINT_2, HINT_1), toasts);

        tap(vm, 1, 200);
        assertEquals(Collections.singletonList(AboutEvent.SHOW_SERVER_DIALOG), events);
    }

    @Test
    public void tapsExactly1500msApartStillCount() {
        AboutViewModel vm = create();
        List<AboutEvent> events = record(vm.getEvent());

        tap(vm, 5, AboutViewModel.TAP_INTERVAL_MS);

        assertEquals(Collections.singletonList(AboutEvent.SHOW_SERVER_DIALOG), events);
    }

    @Test
    public void countRestartsAfterOpening() {
        AboutViewModel vm = create();
        List<AboutEvent> events = record(vm.getEvent());
        List<String> toasts = record(vm.getToast());

        tap(vm, 6, 100);
        assertEquals(1, events.size());
        assertEquals(2, toasts.size());

        tap(vm, 4, 100);
        assertEquals(2, events.size());
        assertEquals(Arrays.asList(HINT_2, HINT_1, HINT_2, HINT_1), toasts);
    }

    // ---------- 服务器地址 ----------

    @Test
    public void defaultServerHidesServerLine() {
        AboutViewModel vm = create();
        assertFalse(value(vm.getCustomServer()));
        assertEquals("", value(vm.getServerText()));
        assertEquals(DEFAULT_SERVER, vm.getCurrentServer());
        assertEquals(DEFAULT_SERVER, vm.getDefaultServer());
        assertFalse(vm.isCustomServer());
    }

    @Test
    public void customServerShowsServerLine() {
        server.saved = "http://47.100.1.2";
        AboutViewModel vm = create();
        assertTrue(value(vm.getCustomServer()));
        assertEquals("服务器：http://47.100.1.2", value(vm.getServerText()));
        assertEquals("http://47.100.1.2", vm.getCurrentServer());
        assertTrue(vm.isCustomServer());
    }

    @Test
    public void invalidAddressChangesNothing() {
        AboutViewModel vm = create();
        for (String input : Arrays.asList(null, "", "  ", "http://47.100.1.2/pestai/v1", "ftp://47.100.1.2",
                "47.100.1.2:99999")) {
            assertEquals(input, AboutViewModel.ServerResult.INVALID, vm.saveServer(input));
        }
        assertEquals(0, userService.logoutCalls);
        assertNull(server.saved);
        assertFalse(vm.isCustomServer());
    }

    @Test
    public void sameAddressIsUnchanged() {
        AboutViewModel vm = create();
        assertEquals(AboutViewModel.ServerResult.UNCHANGED, vm.saveServer("10.0.2.2:8080"));
        assertEquals(AboutViewModel.ServerResult.UNCHANGED, vm.saveServer(" http://10.0.2.2:8080/ "));
        assertEquals(0, userService.logoutCalls);
        assertNull(server.saved);
    }

    @Test
    public void newAddressIsSavedAndClearsLogin() {
        AboutViewModel vm = create();

        assertEquals(AboutViewModel.ServerResult.CHANGED, vm.saveServer(" 47.100.1.2 "));

        assertEquals("http://47.100.1.2", server.saved);
        assertEquals("http://47.100.1.2", server.current());
        assertEquals(1, userService.logoutCalls);
        assertFalse(userService.isLoggedIn());
        assertTrue(value(vm.getCustomServer()));
        assertEquals("服务器：http://47.100.1.2", value(vm.getServerText()));
    }

    @Test
    public void sameCustomAddressInAnotherFormIsUnchanged() {
        server.saved = "http://47.100.1.2";
        AboutViewModel vm = create();
        assertEquals(AboutViewModel.ServerResult.UNCHANGED, vm.saveServer("http://47.100.1.2:80/"));
        assertEquals(0, userService.logoutCalls);
    }

    @Test
    public void savingDefaultWhileCustomRestoresDefault() {
        server.saved = "http://47.100.1.2";
        AboutViewModel vm = create();

        assertEquals(AboutViewModel.ServerResult.CHANGED, vm.saveServer("10.0.2.2:8080"));

        assertNull(server.saved);
        assertFalse(vm.isCustomServer());
        assertEquals(1, userService.logoutCalls);
        assertFalse(value(vm.getCustomServer()));
    }

    @Test
    public void resetWhenCustomClearsLogin() {
        server.saved = "http://47.100.1.2:8080";
        AboutViewModel vm = create();

        assertEquals(AboutViewModel.ServerResult.CHANGED, vm.resetServer());

        assertNull(server.saved);
        assertEquals(DEFAULT_SERVER, vm.getCurrentServer());
        assertEquals(1, userService.logoutCalls);
        assertFalse(value(vm.getCustomServer()));
        assertEquals("", value(vm.getServerText()));
    }

    @Test
    public void resetWhenDefaultIsUnchanged() {
        AboutViewModel vm = create();
        assertEquals(AboutViewModel.ServerResult.UNCHANGED, vm.resetServer());
        assertEquals(0, userService.logoutCalls);
    }
}
