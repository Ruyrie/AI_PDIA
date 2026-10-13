package com.heban.pestai.account.ui.deactivate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.UserServiceImpl;
import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class DeactivateViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private DeactivateViewModel vm;
    private List<String> toasts;
    private List<Void> confirms;
    private List<Void> deactivated;

    @Before
    public void setUp() {
        env = new TestEnv().withLogin();   // 当前手机号 13800005678
        vm = new DeactivateViewModel(env.repository, new UserServiceImpl(env.session));
        toasts = TestEnv.record(vm.getToast());
        confirms = TestEnv.record(vm.getShowConfirm());
        deactivated = TestEnv.record(vm.getDeactivated());
        TestEnv.record(vm.getCanSendCode());
        TestEnv.record(vm.getCodeHint());
        TestEnv.record(vm.getCodeHintError());
        TestEnv.record(vm.getCanSubmit());
    }

    private void fillReady() {
        vm.getCode().setValue("123456");
        vm.getAgreed().setValue(true);
    }

    @Test
    public void currentPhone_isMasked() {
        assertEquals("138****5678", vm.getCurrentMasked());
    }

    @Test
    public void canSubmit_requiresAgreeAndSixDigitCode() {
        assertFalse(vm.getCanSubmit().getValue());
        vm.getCode().setValue("123456");
        assertFalse(vm.getCanSubmit().getValue());   // 未勾选
        vm.getAgreed().setValue(true);
        assertTrue(vm.getCanSubmit().getValue());
        vm.getCode().setValue("12345");
        assertFalse(vm.getCanSubmit().getValue());
        vm.getCode().setValue("123456");
        vm.getAgreed().setValue(false);
        assertFalse(vm.getCanSubmit().getValue());
    }

    @Test
    public void sendCode_toCurrentPhone_startsCountdownAndShowsHint() {
        assertTrue(vm.getCanSendCode().getValue());
        vm.sendCode();
        assertEquals(1, env.api.deactivateSmsCount);
        assertEquals(0, env.api.sendSmsCount);   // 不走 auth/sms/send，号码由服务器决定
        assertEquals("验证码已发送至 138****5678，5 分钟内有效", vm.getCodeHint().getValue());
        assertFalse(vm.getCodeHintError().getValue());
        assertEquals("60s 后重发", vm.getCountdownText().getValue());
        assertFalse(vm.getCanSendCode().getValue());

        rx.passThrottle();
        vm.sendCode();
        assertEquals(1, env.api.deactivateSmsCount);
    }

    @Test
    public void sendCode_tooFrequent_showsSnackbar() {
        env.api.deactivateSmsResult = FakeAuthApi.fail(ApiException.TOO_FREQUENT, "操作太频繁，请稍后再试");
        vm.sendCode();
        assertEquals("操作太频繁，请稍后再试", toasts.get(toasts.size() - 1));
        assertNull(vm.getCodeHint().getValue());
        assertTrue(vm.getCanSendCode().getValue());
    }

    @Test
    public void submit_onlyShowsConfirmDialog() {
        vm.submit();   // 条件未满足
        assertTrue(confirms.isEmpty());

        fillReady();
        rx.passThrottle();
        vm.submit();
        assertEquals(1, confirms.size());
        assertEquals(0, env.api.deactivateCount);   // 二次确认前不提交
    }

    @Test
    public void confirm_success_logsOutAndNavigates() {
        fillReady();
        vm.submit();
        vm.confirmDeactivate();

        assertEquals(1, env.api.deactivateCount);
        assertEquals("123456", env.api.lastDeactivate.getCode());
        // IUserService.logout()：清除 token、user_json、is_guest 并通知其他组件
        assertNull(env.token());
        assertNull(env.userJson());
        assertNull(env.session.userChanges().getValue());
        assertEquals(1, deactivated.size());
        assertFalse(vm.getLoading().getValue());
    }

    @Test
    public void confirm_wrongCode_clearsCodeAndShowsErrorUnderCode() {
        env.api.deactivateResult = FakeAuthApi.fail(ApiException.SMS_CODE_INVALID, "验证码错误或已过期");
        fillReady();
        vm.confirmDeactivate();

        assertEquals("", vm.getCode().getValue());
        assertEquals(AccountTexts.CODE_EXPIRED, vm.getCodeHint().getValue());
        assertTrue(vm.getCodeHintError().getValue());
        assertFalse(vm.getCanSubmit().getValue());
        assertTrue(deactivated.isEmpty());
        assertEquals("token-123", env.token());   // 失败不清除登录态

        vm.getCode().setValue("6");
        assertFalse(vm.getCodeHintError().getValue());
    }

    @Test
    public void confirm_networkError_showsSnackbarAndKeepsLogin() {
        env.api.deactivateResult = FakeAuthApi.fail(ApiException.SERVER_ERROR, null);
        fillReady();
        vm.confirmDeactivate();
        assertEquals(ApiException.defaultMessage(ApiException.SERVER_ERROR), toasts.get(toasts.size() - 1));
        assertEquals("123456", vm.getCode().getValue());
        assertTrue(deactivated.isEmpty());
        assertEquals("token-123", env.token());
    }

    @Test
    public void confirm_throttled() {
        fillReady();
        vm.confirmDeactivate();
        vm.confirmDeactivate();
        assertEquals(1, env.api.deactivateCount);
    }
}
