package com.heban.pestai.account.ui.forgot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.common.vo.ResetTicketVO;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class ForgotPwdViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private ForgotPwdViewModel vm;
    private List<String> toasts;
    private List<ResetTicketVO> verified;

    @Before
    public void setUp() {
        env = new TestEnv();
        vm = new ForgotPwdViewModel(env.repository);
        vm.setClock(() -> 1_000_000L);
        toasts = TestEnv.record(vm.getToast());
        verified = TestEnv.record(vm.getVerified());
        TestEnv.record(vm.getCanNext());
        TestEnv.record(vm.getCanSendCode());
    }

    @Test
    public void init_fromSettings_prefillsAndLocksPhone() {
        vm.init("13800005678", true);
        assertEquals("138 0000 5678", vm.getPhone().getValue());
        assertFalse(vm.getPhoneEditable().getValue());
        assertTrue(vm.isFromSettings());

        vm.init("13900001111", false);   // 只执行一次
        assertEquals("138 0000 5678", vm.getPhone().getValue());
    }

    @Test
    public void init_fromSettingsWithInvalidPhone_fallsBackToEditable() {
        vm.init("", true);
        assertTrue(vm.getPhoneEditable().getValue());
        assertEquals("", vm.getPhone().getValue());
    }

    @Test
    public void init_normalEntry_editable() {
        vm.init(null, false);
        assertTrue(vm.getPhoneEditable().getValue());
        assertFalse(vm.isFromSettings());
    }

    @Test
    public void sendCode_resetScene_showsShortHint() {
        vm.init(null, false);
        vm.getPhone().setValue("13800005678");
        vm.sendCode();
        assertEquals("RESET", env.api.lastSms.getScene());
        assertEquals("已发送至 138****5678", vm.getSentHint().getValue());
        assertEquals("60s 后重发", vm.getCountdownText().getValue());
    }

    @Test
    public void sendCode_notRegistered_showsText() {
        vm.getPhone().setValue("13800005678");
        env.api.sendSmsResult = FakeAuthApi.fail(ApiException.NOT_REGISTERED, "该手机号未注册");
        vm.sendCode();
        assertEquals("该手机号未注册", toasts.get(toasts.size() - 1));
        assertEquals("获取验证码", vm.getCountdownText().getValue());
    }

    @Test
    public void next_success_emitsTicketWithDeadline() {
        vm.getPhone().setValue("138 0000 5678");
        vm.getCode().setValue("482915");
        assertTrue(vm.getCanNext().getValue());
        vm.next();
        assertEquals(1, env.api.verifyResetCodeCount);
        assertEquals("13800005678", env.api.lastVerify.getPhone());
        assertEquals(1, verified.size());
        // 截止时刻 = 当前 + 600 秒 - 30 秒余量
        assertEquals(1_000_000L + 600_000L - 30_000L, vm.getTicketDeadline());
        assertEquals("13800005678", vm.getNormalizedPhone());
    }

    @Test
    public void next_wrongCode_clearsCode() {
        vm.getPhone().setValue("13800005678");
        vm.getCode().setValue("000000");
        env.api.verifyResetCodeResult = FakeAuthApi.fail(ApiException.SMS_CODE_INVALID, "验证码错误或已过期");
        vm.next();
        assertEquals("验证码错误或已过期", toasts.get(toasts.size() - 1));
        assertEquals("", vm.getCode().getValue());
        assertEquals("13800005678", vm.getPhone().getValue());
        assertTrue(verified.isEmpty());
    }

    @Test
    public void canNext_requiresSixDigitCode() {
        vm.getPhone().setValue("13800005678");
        vm.getCode().setValue("4829");
        assertFalse(vm.getCanNext().getValue());
    }

    @Test
    public void onTicketExpired_clearsCodeAndPrompts() {
        vm.getCode().setValue("482915");
        vm.onTicketExpired();
        assertEquals("", vm.getCode().getValue());
        assertEquals(AccountTexts.TICKET_EXPIRED, toasts.get(toasts.size() - 1));
    }
}
