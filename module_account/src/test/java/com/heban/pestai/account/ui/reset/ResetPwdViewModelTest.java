package com.heban.pestai.account.ui.reset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class ResetPwdViewModelTest {

    private static final long NOW = 1_000_000L;

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private ResetPwdViewModel vm;
    private List<String> toasts;
    private List<String> expired;
    private List<Boolean> navigate;

    @Before
    public void setUp() {
        env = new TestEnv();
        vm = new ResetPwdViewModel(env.repository);
        vm.setClock(() -> NOW);
        toasts = TestEnv.record(vm.getToast());
        expired = TestEnv.record(vm.getExpired());
        navigate = TestEnv.record(vm.getNavigateLogin());
        TestEnv.record(vm.getRuleRecent());
        TestEnv.record(vm.getCanSubmit());
        TestEnv.record(vm.getConfirmHint());
        TestEnv.record(vm.getConfirmHintError());
        TestEnv.record(vm.getPasswordError());
    }

    private void fill(String password) {
        vm.getPassword().setValue(password);
        vm.getConfirm().setValue(password);
    }

    @Test
    public void canSubmit_requiresRulesAndMatch() {
        vm.init("13800005678", "ticket", NOW + 60_000, false);
        assertFalse(vm.getCanSubmit().getValue());
        vm.getPassword().setValue("abc12345");
        vm.getConfirm().setValue("abc1234");
        assertFalse(vm.getCanSubmit().getValue());
        assertEquals(AccountTexts.CONFIRM_MISMATCH, vm.getConfirmHint().getValue());
        assertTrue(vm.getConfirmHintError().getValue());

        vm.getConfirm().setValue("abc12345");
        assertTrue(vm.getCanSubmit().getValue());
        assertEquals(AccountTexts.CONFIRM_MATCHED, vm.getConfirmHint().getValue());
        assertFalse(vm.getConfirmHintError().getValue());
    }

    @Test
    public void passwordError_onlyAfterEightChars() {
        vm.getPassword().setValue("abc");
        assertNull(vm.getPasswordError().getValue());
        vm.getPassword().setValue("abcdefgh");
        assertEquals(AccountTexts.PASSWORD_RULE, vm.getPasswordError().getValue());
        vm.getPassword().setValue("abcdefg1");
        assertNull(vm.getPasswordError().getValue());
    }

    @Test
    public void localDeadlinePassed_emitsExpiredWithoutRequest() {
        vm.init("13800005678", "ticket", NOW - 1, false);
        fill("abc12345");
        vm.submit();
        assertEquals(0, env.api.resetPasswordCount);
        assertEquals(1, expired.size());
        assertEquals(AccountTexts.TICKET_EXPIRED, expired.get(0));
    }

    @Test
    public void samePassword_marksThirdRuleFailed_thenResetsOnChange() {
        vm.init("13800005678", "ticket", NOW + 60_000, false);
        fill("abc12345");
        env.api.resetPasswordResult = FakeAuthApi.fail(ApiException.PARAM_ERROR, AccountTexts.SAME_PASSWORD);
        vm.submit();
        assertEquals(1, env.api.resetPasswordCount);
        assertEquals(Integer.valueOf(ResetPwdViewModel.RECENT_FAILED), vm.getRuleRecent().getValue());
        assertEquals(AccountTexts.SAME_PASSWORD, toasts.get(toasts.size() - 1));
        assertFalse(vm.getCanSubmit().getValue());
        assertTrue(expired.isEmpty());

        vm.getPassword().setValue("abc123456");
        assertEquals(Integer.valueOf(ResetPwdViewModel.RECENT_PENDING), vm.getRuleRecent().getValue());
    }

    @Test
    public void otherParamError_treatedAsExpiredTicket() {
        vm.init("13800005678", "ticket", NOW + 60_000, false);
        fill("abc12345");
        env.api.resetPasswordResult = FakeAuthApi.fail(ApiException.PARAM_ERROR, AccountTexts.TICKET_EXPIRED);
        vm.submit();
        assertEquals(1, expired.size());
    }

    @Test
    public void success_showsDoneAndGoLoginWithoutClearingSession() {
        env.withToken("token");
        vm.init("13800005678", "ticket", NOW + 60_000, false);
        fill("abc12345");
        vm.submit();
        assertEquals("ticket", env.api.lastReset.getResetTicket());
        assertEquals("abc12345", env.api.lastReset.getNewPassword());
        assertTrue(vm.getDone().getValue());
        assertEquals("", vm.getPassword().getValue());
        assertEquals("token", env.token());

        vm.goLogin();
        assertEquals(Boolean.FALSE, navigate.get(0));
    }

    @Test
    public void success_fromSettings_clearsSessionButKeepsOnboarding() {
        env.withToken("token").withOnboardingDone();
        env.session.setGuest(false);
        vm.init("13800005678", "ticket", NOW + 60_000, true);
        fill("abc12345");
        vm.submit();
        assertTrue(vm.getDone().getValue());
        assertNull(env.token());
        assertNull(env.userJson());
        assertFalse(env.context.prefs.contains(UserSession.KEY_IS_GUEST));
        assertTrue(env.session.isOnboardingDone());

        vm.goLogin();
        assertEquals(Boolean.TRUE, navigate.get(0));
    }

    @Test
    public void togglePasswordVisible() {
        assertFalse(vm.getPasswordVisible().getValue());
        vm.togglePasswordVisible();
        assertTrue(vm.getPasswordVisible().getValue());
    }
}
