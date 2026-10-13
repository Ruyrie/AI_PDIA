package com.heban.pestai.account.ui.register;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.account.ui.login.LoginEvent;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class RegisterViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private RegisterViewModel vm;
    private List<String> toasts;
    private List<LoginEvent> results;

    @Before
    public void setUp() {
        env = new TestEnv();
        vm = new RegisterViewModel(env.repository);
        toasts = TestEnv.record(vm.getToast());
        results = TestEnv.record(vm.getResult());
        TestEnv.record(vm.getStrengthLevel());
        TestEnv.record(vm.getRuleLength());
        TestEnv.record(vm.getRuleLetter());
        TestEnv.record(vm.getRuleDigit());
        TestEnv.record(vm.getConfirmMatched());
        TestEnv.record(vm.getConfirmError());
        TestEnv.record(vm.getCanNext());
        TestEnv.record(vm.getAgreementHint());
    }

    private void fillValidForm() {
        vm.getPhone().setValue("138 0000 5678");
        vm.getCode().setValue("482915");
        vm.getPassword().setValue("abc12345");
        vm.getConfirm().setValue("abc12345");
    }

    @Test
    public void passwordRulesAndStrength_updateInRealTime() {
        vm.getPassword().setValue("12345678");
        assertEquals(Integer.valueOf(1), vm.getStrengthLevel().getValue());
        assertTrue(vm.getRuleLength().getValue());
        assertFalse(vm.getRuleLetter().getValue());
        assertTrue(vm.getRuleDigit().getValue());

        vm.getPassword().setValue("abc12345");
        assertEquals(Integer.valueOf(2), vm.getStrengthLevel().getValue());
        assertTrue(vm.getRuleLetter().getValue());

        vm.getPassword().setValue("Abc12345!");
        assertEquals(Integer.valueOf(3), vm.getStrengthLevel().getValue());
    }

    @Test
    public void confirm_matchedAndMismatchError() {
        vm.getPassword().setValue("abc12345");
        vm.getConfirm().setValue("abc");
        assertFalse(vm.getConfirmMatched().getValue());
        assertNull(vm.getConfirmError().getValue());          // 还在输入，不提示

        vm.getConfirm().setValue("abc12346");
        assertEquals(AccountTexts.CONFIRM_MISMATCH, vm.getConfirmError().getValue());

        vm.getConfirm().setValue("abc12345");
        assertTrue(vm.getConfirmMatched().getValue());
        assertNull(vm.getConfirmError().getValue());
    }

    @Test
    public void canNext_requiresAllFields() {
        assertFalse(vm.getCanNext().getValue());
        fillValidForm();
        assertTrue(vm.getCanNext().getValue());
        vm.getCode().setValue("48291");
        assertFalse(vm.getCanNext().getValue());
        vm.getCode().setValue("482915");
        vm.getPassword().setValue("abcdefgh");
        assertFalse(vm.getCanNext().getValue());
    }

    @Test
    public void next_withoutAgreement_noRequest() {
        fillValidForm();
        vm.next();
        assertTrue(vm.getAgreementHint().getValue());
        assertEquals(0, env.api.registerCount);
    }

    @Test
    public void register_success_goesProfileSetup() {
        fillValidForm();
        vm.getAgreed().setValue(true);
        vm.next();
        assertEquals(1, env.api.registerCount);
        assertEquals("13800005678", env.api.lastRegister.getPhone());
        assertEquals("482915", env.api.lastRegister.getCode());
        assertEquals(LoginEvent.GO_PROFILE_SETUP, results.get(0));
        assertEquals("token-123", env.token());
    }

    @Test
    public void register_phoneRegistered_showsText() {
        fillValidForm();
        vm.getAgreed().setValue(true);
        env.api.registerResult = FakeAuthApi.fail(ApiException.PHONE_REGISTERED, "该手机号已注册");
        vm.next();
        assertEquals("该手机号已注册，请直接登录", toasts.get(toasts.size() - 1));
        assertTrue(results.isEmpty());
        assertEquals("abc12345", vm.getPassword().getValue());   // 表单保留
    }

    @Test
    public void sendCode_usesRegisterScene() {
        vm.getPhone().setValue("13800005678");
        vm.sendCode();
        assertEquals("REGISTER", env.api.lastSms.getScene());
        assertEquals("60s 后重发", vm.getCountdownText().getValue());
    }
}
