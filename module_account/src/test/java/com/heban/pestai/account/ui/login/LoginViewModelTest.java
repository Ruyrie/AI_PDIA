package com.heban.pestai.account.ui.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class LoginViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private LoginViewModel vm;
    private List<String> toasts;
    private List<LoginEvent> results;

    @Before
    public void setUp() {
        env = new TestEnv();
        vm = new LoginViewModel(env.repository);
        toasts = TestEnv.record(vm.getToast());
        results = TestEnv.record(vm.getResult());
        TestEnv.record(vm.getAgreementHint());
        TestEnv.record(vm.getCanSendCode());
        TestEnv.record(vm.getCountdownText());
    }

    private void fillPasswordForm() {
        vm.getPhone().setValue("138 0000 5678");
        vm.getPassword().setValue("abc12345");
    }

    @Test
    public void defaults_passwordModeAndAgreementUnchecked() {
        assertEquals(LoginViewModel.Mode.PASSWORD, vm.getMode().getValue());
        assertFalse(vm.getAgreed().getValue());
    }

    @Test
    public void login_withoutAgreement_showsHintAndSendsNothing() {
        List<Void> shakes = TestEnv.record(vm.getAgreementShake());
        fillPasswordForm();
        vm.login();
        assertTrue(vm.getAgreementHint().getValue());
        assertEquals(1, shakes.size());
        assertEquals(0, env.api.loginByPasswordCount);

        vm.getAgreed().setValue(true);   // 勾选后提示消失
        assertFalse(vm.getAgreementHint().getValue());
    }

    @Test
    public void passwordLogin_success_goesMainAndSavesToken() {
        fillPasswordForm();
        vm.getAgreed().setValue(true);
        vm.login();
        assertEquals(1, env.api.loginByPasswordCount);
        assertEquals("13800005678", env.api.lastPasswordLogin.getPhone());
        assertEquals(LoginEvent.GO_MAIN, results.get(0));
        assertEquals("token-123", env.token());
        assertFalse(vm.getLoading().getValue());
    }

    @Test
    public void login_rapidClicks_sendOnlyOneRequest() {
        fillPasswordForm();
        vm.getAgreed().setValue(true);
        vm.login();
        vm.login();
        vm.login();
        assertEquals(1, env.api.loginByPasswordCount);
        rx.passThrottle();
        vm.login();
        assertEquals(2, env.api.loginByPasswordCount);
    }

    @Test
    public void invalidPhone_showsToast() {
        vm.getPhone().setValue("1380000");
        vm.getPassword().setValue("abc12345");
        vm.getAgreed().setValue(true);
        vm.login();
        assertEquals(AccountTexts.PHONE_INVALID, toasts.get(0));
        assertEquals(0, env.api.loginByPasswordCount);
    }

    @Test
    public void errorCodes_mapToDocumentTexts() {
        fillPasswordForm();
        vm.getAgreed().setValue(true);

        env.api.loginByPasswordResult = FakeAuthApi.fail(ApiException.WRONG_PASSWORD, "账号或密码错误");
        vm.login();
        assertEquals("账号或密码错误", last(toasts));

        rx.passThrottle();
        env.api.loginByPasswordResult = FakeAuthApi.fail(ApiException.NOT_REGISTERED, "该手机号未注册");
        vm.login();
        assertEquals("该手机号未注册，可以使用验证码登录自动注册", last(toasts));

        rx.passThrottle();
        env.api.loginByPasswordResult = FakeAuthApi.fail(ApiException.ACCOUNT_DISABLED, null);
        vm.login();
        assertEquals("该账号已停用", last(toasts));
        assertTrue(results.isEmpty());
    }

    @Test
    public void smsLogin_newUser_goesProfileSetup() {
        vm.switchMode(LoginViewModel.Mode.SMS);
        assertTrue(TestEnv.value(vm.getSmsMode()));
        vm.getPhone().setValue("13800005678");
        vm.getCode().setValue("048215");
        vm.getAgreed().setValue(true);
        env.api.loginBySmsResult = FakeAuthApi.ok(FakeAuthApi.login(true));
        vm.login();
        assertEquals(1, env.api.loginBySmsCount);
        assertEquals(LoginEvent.GO_PROFILE_SETUP, results.get(0));
    }

    @Test
    public void smsLogin_wrongCode_showsTextAndSelectsCode() {
        List<Void> selects = TestEnv.record(vm.getSelectCode());
        vm.switchMode(LoginViewModel.Mode.SMS);
        vm.getPhone().setValue("13800005678");
        vm.getCode().setValue("000000");
        vm.getAgreed().setValue(true);
        env.api.loginBySmsResult = FakeAuthApi.fail(ApiException.SMS_CODE_INVALID, "验证码错误或已过期");
        vm.login();
        assertEquals("验证码错误或已过期", last(toasts));
        assertEquals(1, selects.size());
        assertEquals("000000", vm.getCode().getValue());   // 内容保留
    }

    @Test
    public void smsLogin_requiresSixDigitCode() {
        vm.switchMode(LoginViewModel.Mode.SMS);
        vm.getPhone().setValue("13800005678");
        vm.getCode().setValue("123");
        vm.getAgreed().setValue(true);
        vm.login();
        assertEquals(AccountTexts.CODE_INVALID, last(toasts));
        assertEquals(0, env.api.loginBySmsCount);
    }

    @Test
    public void sendCode_startsCountdownAndDisablesButton() {
        vm.switchMode(LoginViewModel.Mode.SMS);
        vm.getPhone().setValue("138 0000 5678");
        assertTrue(vm.getCanSendCode().getValue());

        vm.sendCode();
        assertEquals(1, env.api.sendSmsCount);
        assertEquals("LOGIN", env.api.lastSms.getScene());
        assertEquals("验证码已发送至 138****5678，5 分钟内有效", vm.getSentHint().getValue());
        assertEquals("60s 后重发", vm.getCountdownText().getValue());
        assertFalse(vm.getCanSendCode().getValue());

        rx.advance(1000);
        vm.sendCode();   // 倒计时期间不可再发
        assertEquals(1, env.api.sendSmsCount);

        rx.advance(59_000);
        assertEquals("获取验证码", vm.getCountdownText().getValue());
        assertTrue(vm.getCanSendCode().getValue());
    }

    @Test
    public void sendCode_tooFrequent_noCountdown() {
        vm.getPhone().setValue("13800005678");
        env.api.sendSmsResult = FakeAuthApi.fail(ApiException.TOO_FREQUENT, "操作太频繁，请稍后再试");
        vm.sendCode();
        assertEquals("操作太频繁，请稍后再试", last(toasts));
        assertEquals("获取验证码", vm.getCountdownText().getValue());
        assertTrue(vm.getCanSendCode().getValue());
    }

    @Test
    public void switchMode_keepsPhoneAndAgreement() {
        vm.getPhone().setValue("13800005678");
        vm.getAgreed().setValue(true);
        vm.switchMode(LoginViewModel.Mode.SMS);
        vm.switchMode(LoginViewModel.Mode.PASSWORD);
        assertEquals("13800005678", vm.getPhone().getValue());
        assertTrue(vm.getAgreed().getValue());
    }

    @Test
    public void enterGuest_marksGuestAndGoesMain() {
        env.withToken("old");
        vm.enterGuest();
        assertTrue(env.session.isGuest());
        assertEquals(null, env.token());
        assertEquals(LoginEvent.GO_MAIN, results.get(0));
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }
}
