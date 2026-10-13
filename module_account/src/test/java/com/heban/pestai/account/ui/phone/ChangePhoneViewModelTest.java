package com.heban.pestai.account.ui.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

public class ChangePhoneViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private ChangePhoneViewModel vm;
    private List<String> toasts;
    private List<Void> finish;

    @Before
    public void setUp() {
        env = new TestEnv().withLogin();   // 当前手机号 13800005678
        vm = new ChangePhoneViewModel(env.repository);
        toasts = TestEnv.record(vm.getToast());
        finish = TestEnv.record(vm.getFinish());
        TestEnv.record(vm.getNewPhoneError());
        TestEnv.record(vm.getCanSendCode());
        TestEnv.record(vm.getCodeHint());
        TestEnv.record(vm.getCodeHintError());
        TestEnv.record(vm.getCanSubmit());
    }

    private void fill(String phone, String code) {
        vm.getNewPhone().setValue(phone);
        vm.getCode().setValue(code);
    }

    @Test
    public void currentPhone_isMasked() {
        assertEquals("138****5678", vm.getCurrentMasked());
    }

    @Test
    public void samePhoneAsCurrent_localErrorBlocksSendAndSubmit() {
        fill("138 0000 5678", "123456");
        assertEquals(AccountTexts.SAME_CURRENT_PHONE, vm.getNewPhoneError().getValue());
        assertFalse(vm.getCanSendCode().getValue());
        assertFalse(vm.getCanSubmit().getValue());

        vm.sendCode();
        vm.submit();
        assertEquals(0, env.api.changePhoneSmsCount);
        assertEquals(0, env.api.changePhoneCount);

        vm.getNewPhone().setValue("139 0000 1111");
        assertNull(vm.getNewPhoneError().getValue());
        assertTrue(vm.getCanSendCode().getValue());
        assertTrue(vm.getCanSubmit().getValue());
    }

    @Test
    public void phoneFormat_errorOnlyWhenDefinitelyInvalid() {
        vm.getNewPhone().setValue("139");
        assertNull(vm.getNewPhoneError().getValue());
        assertFalse(vm.getCanSendCode().getValue());
        vm.getNewPhone().setValue("239");
        assertEquals(AccountTexts.PHONE_INVALID, vm.getNewPhoneError().getValue());
    }

    @Test
    public void canSubmit_requiresValidPhoneAndSixDigitCode() {
        assertFalse(vm.getCanSubmit().getValue());
        fill("13900001111", "12345");
        assertFalse(vm.getCanSubmit().getValue());
        vm.getCode().setValue("123456");
        assertTrue(vm.getCanSubmit().getValue());
        vm.getNewPhone().setValue("1390000111");
        assertFalse(vm.getCanSubmit().getValue());
    }

    @Test
    public void sendCode_toNewPhone_startsCountdownAndShowsHint() {
        vm.getNewPhone().setValue("139 0000 1111");
        vm.sendCode();
        assertEquals(1, env.api.changePhoneSmsCount);
        assertEquals("13900001111", env.api.lastChangePhoneSms.getNewPhone());
        assertEquals(0, env.api.sendSmsCount);
        assertEquals("验证码已发送至 139****1111，5 分钟内有效", vm.getCodeHint().getValue());
        assertFalse(vm.getCodeHintError().getValue());
        assertEquals("60s 后重发", vm.getCountdownText().getValue());
        assertFalse(vm.getCanSendCode().getValue());

        // 倒计时中再次点击不发送
        rx.passThrottle();
        vm.sendCode();
        assertEquals(1, env.api.changePhoneSmsCount);

        rx.advance(59_000);
        assertEquals("获取验证码", vm.getCountdownText().getValue());
        assertTrue(vm.getCanSendCode().getValue());
    }

    @Test
    public void sentHint_hiddenAfterPhoneChanged() {
        vm.getNewPhone().setValue("13900001111");
        vm.sendCode();
        vm.getNewPhone().setValue("13700001111");
        assertNull(vm.getCodeHint().getValue());
        vm.getNewPhone().setValue("13900001111");
        assertEquals("验证码已发送至 139****1111，5 分钟内有效", vm.getCodeHint().getValue());
    }

    @Test
    public void sendCode_phoneRegistered_showsErrorUnderPhoneUntilChanged() {
        env.api.changePhoneSmsResult = FakeAuthApi.fail(ApiException.PHONE_REGISTERED, "该手机号已注册");
        vm.getNewPhone().setValue("13900001111");
        vm.sendCode();
        assertEquals(AccountTexts.PHONE_REGISTERED_CHANGE, vm.getNewPhoneError().getValue());
        assertFalse(vm.getCanSendCode().getValue());
        assertEquals("获取验证码", vm.getCountdownText().getValue());   // 没有开始倒计时
        assertTrue(toasts.isEmpty());

        vm.getNewPhone().setValue("13700001111");
        assertNull(vm.getNewPhoneError().getValue());
        assertTrue(vm.getCanSendCode().getValue());

        // 改回原号码，错误仍然显示
        vm.getNewPhone().setValue("139 0000 1111");
        assertEquals(AccountTexts.PHONE_REGISTERED_CHANGE, vm.getNewPhoneError().getValue());
    }

    @Test
    public void sendCode_serverSaysSamePhone_showsErrorUnderPhone() {
        env.api.changePhoneSmsResult = FakeAuthApi.fail(ApiException.PARAM_ERROR, AccountTexts.SAME_CURRENT_PHONE);
        vm.getNewPhone().setValue("13900001111");
        vm.sendCode();
        assertEquals(AccountTexts.SAME_CURRENT_PHONE, vm.getNewPhoneError().getValue());
        assertTrue(toasts.isEmpty());
    }

    @Test
    public void sendCode_tooFrequent_showsSnackbar() {
        env.api.changePhoneSmsResult = FakeAuthApi.fail(ApiException.TOO_FREQUENT, "操作太频繁，请稍后再试");
        vm.getNewPhone().setValue("13900001111");
        vm.sendCode();
        assertEquals("操作太频繁，请稍后再试", toasts.get(toasts.size() - 1));
        assertNull(vm.getNewPhoneError().getValue());
        assertTrue(vm.getCanSendCode().getValue());
    }

    @Test
    public void submit_success_savesNewTokenAndShowsSuccess() {
        fill("139 0000 1111", "123456");
        vm.submit();

        assertEquals(1, env.api.changePhoneCount);
        assertEquals("13900001111", env.api.lastChangePhone.getNewPhone());
        assertEquals("123456", env.api.lastChangePhone.getCode());
        // 本机保持登录：保存服务器返回的新令牌与新手机号
        assertEquals("token-new", env.token());
        assertTrue(env.session.isLoggedIn());
        assertEquals("13900001111", env.session.userChanges().getValue().getPhone());

        assertTrue(vm.getDone().getValue());
        assertEquals("今后请使用 139****1111 登录", vm.getSuccessDesc().getValue());
        assertFalse(vm.getLoading().getValue());

        vm.finishPage();
        assertEquals(1, finish.size());
    }

    @Test
    public void submit_wrongCode_clearsCodeAndShowsErrorUnderCode() {
        env.api.changePhoneResult = FakeAuthApi.fail(ApiException.SMS_CODE_INVALID, "验证码错误或已过期");
        fill("13900001111", "123456");
        vm.submit();

        assertEquals("", vm.getCode().getValue());
        assertEquals(AccountTexts.CODE_EXPIRED, vm.getCodeHint().getValue());
        assertTrue(vm.getCodeHintError().getValue());
        assertFalse(vm.getDone().getValue());
        assertEquals("token-123", env.token());
        assertTrue(toasts.isEmpty());

        vm.getCode().setValue("6");   // 重新输入后错误提示消失
        assertFalse(vm.getCodeHintError().getValue());
    }

    @Test
    public void submit_phoneTakenMeanwhile_showsErrorUnderPhone() {
        env.api.changePhoneResult = FakeAuthApi.fail(ApiException.PHONE_REGISTERED, "该手机号已注册");
        fill("13900001111", "123456");
        vm.submit();
        assertEquals(AccountTexts.PHONE_REGISTERED_CHANGE, vm.getNewPhoneError().getValue());
        assertFalse(vm.getCanSubmit().getValue());
        assertFalse(vm.getDone().getValue());
    }

    @Test
    public void submit_otherError_showsSnackbarAndKeepsForm() {
        env.api.changePhoneResult = FakeAuthApi.fail(ApiException.SERVER_ERROR, null);
        fill("13900001111", "123456");
        vm.submit();
        assertEquals(ApiException.defaultMessage(ApiException.SERVER_ERROR), toasts.get(toasts.size() - 1));
        assertEquals("123456", vm.getCode().getValue());
        assertTrue(vm.getCanSubmit().getValue());
    }

    @Test
    public void submit_throttledAndIgnoredAfterDone() {
        fill("13900001111", "123456");
        vm.submit();
        vm.submit();   // 500ms 内的连点
        assertEquals(1, env.api.changePhoneCount);

        rx.passThrottle();
        vm.submit();   // 已成功
        assertEquals(1, env.api.changePhoneCount);
    }
}
