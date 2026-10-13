package com.heban.pestai.account.ui.splash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.ViewModelStore;

import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

public class SplashViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;

    @Before
    public void setUp() {
        env = new TestEnv();
    }

    private List<SplashViewModel.Destination> start(SplashViewModel vm) {
        List<SplashViewModel.Destination> destinations = TestEnv.record(vm.getDestination());
        vm.start();
        return destinations;
    }

    @Test
    public void firstLaunch_goesToOnboardingAfter1500ms_withoutRequest() {
        env.withToken("stale-token");   // 未看过引导时即使残留令牌也不校验
        SplashViewModel vm = new SplashViewModel(env.repository);
        List<SplashViewModel.Destination> destinations = start(vm);

        rx.advance(1499);
        assertTrue(destinations.isEmpty());
        rx.advance(1);
        assertEquals(1, destinations.size());
        assertEquals(SplashViewModel.Destination.ONBOARDING, destinations.get(0));
        assertEquals(0, env.api.meCount);
    }

    @Test
    public void onboardedWithoutToken_goesToLogin() {
        env.withOnboardingDone();
        SplashViewModel vm = new SplashViewModel(env.repository);
        List<SplashViewModel.Destination> destinations = start(vm);

        rx.advance(1500);
        assertEquals(SplashViewModel.Destination.LOGIN, destinations.get(0));
        assertFalse(vm.isSessionExpired());
        assertEquals(0, env.api.meCount);
    }

    @Test
    public void validToken_goesToMain_andRefreshesUserJson() {
        env.withOnboardingDone().withToken("token");
        SplashViewModel vm = new SplashViewModel(env.repository);
        List<SplashViewModel.Destination> destinations = start(vm);

        rx.advance(1499);
        assertTrue(destinations.isEmpty());   // 请求已返回，但计时未到不跳转
        rx.advance(1);
        assertEquals(SplashViewModel.Destination.MAIN, destinations.get(0));
        assertEquals(1, env.api.meCount);
        assertTrue(env.userJson().contains("老李种菜"));
    }

    @Test
    public void expiredToken_goesToLoginWithSessionExpired() {
        env.withOnboardingDone().withToken("token");
        env.api.meResult = FakeAuthApi.fail(ApiException.UNAUTHORIZED, "未登录或登录已过期，请重新登录");
        SplashViewModel vm = new SplashViewModel(env.repository);
        List<SplashViewModel.Destination> destinations = start(vm);

        rx.advance(1500);
        assertEquals(SplashViewModel.Destination.LOGIN, destinations.get(0));
        assertTrue(vm.isSessionExpired());
        assertNull(env.token());
    }

    @Test
    public void networkError_treatedAsLoggedIn() {
        env.withOnboardingDone().withToken("token");
        env.api.meResult = Single.error(new IOException("offline"));
        SplashViewModel vm = new SplashViewModel(env.repository);
        List<SplashViewModel.Destination> destinations = start(vm);

        rx.advance(1500);
        assertEquals(SplashViewModel.Destination.MAIN, destinations.get(0));
        assertEquals("token", env.token());
    }

    @Test
    public void slowServer_timesOutAfterTwoSecondsAsValid() {
        env.withOnboardingDone().withToken("token");
        env.api.meResult = Single.never();
        SplashViewModel vm = new SplashViewModel(env.repository);
        List<SplashViewModel.Destination> destinations = start(vm);

        rx.advance(1500);
        assertTrue(destinations.isEmpty());   // 计时已到，但校验还在等待
        rx.advance(499);
        assertTrue(destinations.isEmpty());
        rx.advance(1);                         // 2 秒超时
        assertEquals(SplashViewModel.Destination.MAIN, destinations.get(0));
    }

    @Test
    public void start_onlyRunsOnce() {
        env.withOnboardingDone().withToken("token");
        SplashViewModel vm = new SplashViewModel(env.repository);
        List<SplashViewModel.Destination> destinations = start(vm);
        vm.start();   // 旋转屏幕后再次调用
        rx.advance(3000);
        assertEquals(1, destinations.size());
        assertEquals(1, env.api.meCount);
    }

    @Test
    public void cleared_beforeTimerEnds_doesNotNavigate() {
        SplashViewModel vm = new SplashViewModel(env.repository);
        ViewModelStore store = TestEnv.attach(vm);
        List<SplashViewModel.Destination> destinations = start(vm);
        rx.advance(500);
        store.clear();   // 闪屏期间按返回键退出，ViewModel onCleared() 释放订阅
        rx.advance(5000);
        assertTrue(destinations.isEmpty());
    }

    @Test
    public void versionText() {
        SplashViewModel vm = new SplashViewModel(env.repository);
        vm.setVersionName("1.0.0");
        assertEquals("AI 病虫害识别 · v1.0.0", vm.getVersionText().getValue());
    }
}
