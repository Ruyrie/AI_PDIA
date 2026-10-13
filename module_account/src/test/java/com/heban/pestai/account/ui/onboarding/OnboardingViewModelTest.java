package com.heban.pestai.account.ui.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class OnboardingViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private OnboardingViewModel vm;
    private List<OnboardingViewModel.Event> events;

    @Before
    public void setUp() {
        env = new TestEnv();
        vm = new OnboardingViewModel(env.repository);
        events = TestEnv.record(vm.getEvent());
        TestEnv.record(vm.getIsLast());
    }

    @Test
    public void threePagesFromLocalConstants() {
        assertEquals(3, vm.getPageCount());
        assertEquals(3, vm.getPages().getValue().size());
        assertEquals(Integer.valueOf(0), vm.getCurrentPage().getValue());
        assertFalse(vm.getIsLast().getValue());
    }

    @Test
    public void primaryClick_nextPageUntilLastThenRegister() {
        vm.onPrimaryClick();
        assertEquals(OnboardingViewModel.Event.NEXT_PAGE, events.get(0));
        assertFalse(env.session.isOnboardingDone());

        vm.onPageSelected(2);
        assertTrue(vm.getIsLast().getValue());
        rx.passThrottle();
        vm.onPrimaryClick();
        assertEquals(OnboardingViewModel.Event.GO_REGISTER, events.get(1));
        assertTrue(env.session.isOnboardingDone());
    }

    @Test
    public void primaryClick_throttledWithin500ms() {
        vm.onPrimaryClick();
        vm.onPrimaryClick();
        vm.onPrimaryClick();
        assertEquals(1, events.size());
        rx.advance(500);
        vm.onPrimaryClick();
        assertEquals(2, events.size());
    }

    @Test
    public void skipAndLogin_writeFlagAndGoLogin() {
        vm.onSkip();
        assertEquals(OnboardingViewModel.Event.GO_LOGIN, events.get(0));
        assertTrue(env.session.isOnboardingDone());
        vm.onLogin();
        assertEquals(OnboardingViewModel.Event.GO_LOGIN, events.get(1));
    }

    @Test
    public void onPageSelected_ignoresSameValue() {
        List<Integer> pages = TestEnv.record(vm.getCurrentPage());
        vm.onPageSelected(0);
        vm.onPageSelected(1);
        vm.onPageSelected(1);
        assertEquals(2, pages.size());   // 初始 0 + 切换到 1
    }
}
