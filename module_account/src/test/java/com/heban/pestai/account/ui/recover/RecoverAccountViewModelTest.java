package com.heban.pestai.account.ui.recover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.common.constant.RecoverMethod;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class RecoverAccountViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private RecoverAccountViewModel vm;
    private List<String> toasts;
    private List<Void> toMain;

    @Before
    public void setUp() {
        env = new TestEnv();
        vm = new RecoverAccountViewModel(env.repository);
        toasts = TestEnv.record(vm.getToast());
        toMain = TestEnv.record(vm.getNavigateMain());
        TestEnv.record(vm.getCanNext());
        TestEnv.record(vm.getOldPhoneError());
        TestEnv.record(vm.getPasswordMethod());
        TestEnv.record(vm.getRecoveryCodeHint());
        TestEnv.record(vm.getRecoveryCodeHintError());
        TestEnv.record(vm.getNeedNewPassword());
        TestEnv.record(vm.getNewPhoneError());
        TestEnv.record(vm.getCanSendCode());
        TestEnv.record(vm.getCodeHint());
        TestEnv.record(vm.getCodeHintError());
        TestEnv.record(vm.getCanSubmit());
        TestEnv.record(vm.getConfirmHint());
        TestEnv.record(vm.getNewPasswordError());
    }

    private static String last(List<String> list) {
        return list.get(list.size() - 1);
    }

    private int step() {
        return vm.getStep().getValue();
    }

    /** 第 1 步用登录密码通过 */
    private void passByPassword() {
        vm.getOldPhone().setValue("138 0000 5678");
        vm.getPassword().setValue("abc12345");
        vm.next();
        assertEquals(RecoverAccountViewModel.STEP_NEW_PHONE, step());
    }

    /** 第 1 步用恢复码通过 */
    private void passByRecoveryCode() {
        env.api.recoverVerifyResult = FakeAuthApi.ok(FakeAuthApi.rebindTicket(600, RecoverMethod.RECOVERY_CODE));
        vm.getOldPhone().setValue("13800005678");
        vm.selectMethod(RecoverMethod.RECOVERY_CODE);
        vm.getRecoveryCode().setValue("ABCD-EFGH-JKLM");
        vm.next();
        assertEquals(RecoverAccountViewModel.STEP_NEW_PHONE, step());
    }

    private void fillNewPhoneAndCode() {
        vm.getNewPhone().setValue("139 0000 1111");
        vm.getCode().setValue("123456");
    }

    // ---------- 第 1 步：验证身份 ----------

    @Test
    public void initialState_passwordMethodAndNoUnverifiableFields() {
        assertEquals(RecoverAccountViewModel.STEP_VERIFY, step());
        assertEquals(RecoverMethod.PASSWORD, vm.getMethod().getValue());
        assertTrue(vm.getPasswordMethod().getValue());
        assertFalse(vm.getCanNext().getValue());
        assertEquals(AccountTexts.RECOVERY_CODE_HINT, vm.getRecoveryCodeHint().getValue());
    }

    @Test
    public void canNext_passwordMethod_requiresValidPhoneAndPassword() {
        vm.getOldPhone().setValue("1380000567");
        vm.getPassword().setValue("x");
        assertFalse(vm.getCanNext().getValue());
        vm.getOldPhone().setValue("138 0000 5678");
        assertTrue(vm.getCanNext().getValue());
        vm.getPassword().setValue("");
        assertFalse(vm.getCanNext().getValue());
    }

    @Test
    public void canNext_recoveryCodeMethod_requiresTwelveValidChars() {
        vm.getOldPhone().setValue("13800005678");
        vm.getPassword().setValue("abc12345");
        vm.selectMethod(RecoverMethod.RECOVERY_CODE);
        assertFalse(vm.getPasswordMethod().getValue());
        assertFalse(vm.getCanNext().getValue());   // 切换后只看恢复码

        vm.getRecoveryCode().setValue("ABCD-EFGH-JKL");
        assertFalse(vm.getCanNext().getValue());
        vm.getRecoveryCode().setValue("ABCD-EFGH-JKL0");          // 含字符集之外的 0
        assertFalse(vm.getCanNext().getValue());
        assertEquals(AccountTexts.RECOVERY_CODE_CHARS, vm.getRecoveryCodeHint().getValue());
        assertTrue(vm.getRecoveryCodeHintError().getValue());

        vm.getRecoveryCode().setValue("abcd-efgh-jklm");
        assertTrue(vm.getCanNext().getValue());
        assertEquals(AccountTexts.RECOVERY_CODE_HINT, vm.getRecoveryCodeHint().getValue());
        assertFalse(vm.getRecoveryCodeHintError().getValue());
    }

    @Test
    public void next_password_sendsPasswordAndSkipsNewPasswordFields() {
        passByPassword();
        assertEquals(1, env.api.recoverVerifyCount);
        assertEquals("13800005678", env.api.lastRecoverVerify.getOldPhone());
        assertEquals(RecoverMethod.PASSWORD, env.api.lastRecoverVerify.getMethod());
        assertEquals("abc12345", env.api.lastRecoverVerify.getPassword());
        assertNull(env.api.lastRecoverVerify.getRecoveryCode());
        assertFalse(vm.getNeedNewPassword().getValue());

        // 第 2 步只需要新手机号与验证码
        fillNewPhoneAndCode();
        assertTrue(vm.getCanSubmit().getValue());
    }

    @Test
    public void next_recoveryCode_sendsNormalizedCodeAndRequiresNewPassword() {
        passByRecoveryCode();
        assertEquals(RecoverMethod.RECOVERY_CODE, env.api.lastRecoverVerify.getMethod());
        assertEquals("ABCDEFGHJKLM", env.api.lastRecoverVerify.getRecoveryCode());
        assertNull(env.api.lastRecoverVerify.getPassword());
        assertTrue(vm.getNeedNewPassword().getValue());

        fillNewPhoneAndCode();
        assertFalse(vm.getCanSubmit().getValue());   // 还没有设置新密码
        vm.getNewPassword().setValue("abcdefgh");
        vm.getConfirm().setValue("abcdefgh");
        assertEquals(AccountTexts.PASSWORD_RULE, vm.getNewPasswordError().getValue());
        assertFalse(vm.getCanSubmit().getValue());   // 不符合规则
        vm.getNewPassword().setValue("abc12345");
        assertEquals(AccountTexts.CONFIRM_MISMATCH, vm.getConfirmHint().getValue());
        assertFalse(vm.getCanSubmit().getValue());   // 两次不一致
        vm.getConfirm().setValue("abc12345");
        assertEquals(AccountTexts.CONFIRM_MATCHED, vm.getConfirmHint().getValue());
        assertTrue(vm.getCanSubmit().getValue());
    }

    @Test
    public void verify_notRegistered_showsBelowOldPhoneUntilChanged() {
        env.api.recoverVerifyResult = FakeAuthApi.fail(ApiException.NOT_REGISTERED, "该手机号未注册");
        vm.getOldPhone().setValue("13800005678");
        vm.getPassword().setValue("abc12345");
        vm.next();
        assertEquals(RecoverAccountViewModel.STEP_VERIFY, step());
        assertEquals(AccountTexts.NOT_REGISTERED, vm.getOldPhoneError().getValue());
        assertFalse(vm.getCanNext().getValue());
        assertTrue(toasts.isEmpty());

        vm.getOldPhone().setValue("13800005679");
        assertNull(vm.getOldPhoneError().getValue());
        assertTrue(vm.getCanNext().getValue());
    }

    @Test
    public void verify_wrongPassword_showsBelowPasswordUntilEdited() {
        env.api.recoverVerifyResult = FakeAuthApi.fail(ApiException.WRONG_PASSWORD, "账号或密码错误");
        vm.getOldPhone().setValue("13800005678");
        vm.getPassword().setValue("abc12345");
        vm.next();
        assertEquals(AccountTexts.WRONG_PASSWORD, vm.getPasswordError().getValue());
        assertNull(vm.getOldPhoneError().getValue());
        assertTrue(toasts.isEmpty());

        vm.getPassword().setValue("abc123456");
        assertNull(vm.getPasswordError().getValue());
    }

    @Test
    public void verify_recoveryCodeInvalid_showsBelowRecoveryCodeUntilEdited() {
        env.api.recoverVerifyResult = FakeAuthApi.fail(ApiException.RECOVERY_CODE_INVALID, "恢复码错误或已失效");
        vm.getOldPhone().setValue("13800005678");
        vm.selectMethod(RecoverMethod.RECOVERY_CODE);
        vm.getRecoveryCode().setValue("ABCD-EFGH-JKLM");
        vm.next();
        assertEquals(RecoverAccountViewModel.STEP_VERIFY, step());
        assertEquals(AccountTexts.RECOVERY_CODE_INVALID, vm.getRecoveryCodeHint().getValue());
        assertTrue(vm.getRecoveryCodeHintError().getValue());
        assertTrue(toasts.isEmpty());

        vm.getRecoveryCode().setValue("ABCD-EFGH-JKLN");
        assertEquals(AccountTexts.RECOVERY_CODE_HINT, vm.getRecoveryCodeHint().getValue());
        assertFalse(vm.getRecoveryCodeHintError().getValue());
    }

    @Test
    public void verify_accountDisabled_showsSnackbarWithoutServiceWording() {
        env.api.recoverVerifyResult = FakeAuthApi.fail(ApiException.ACCOUNT_DISABLED, null);
        vm.getOldPhone().setValue("13800005678");
        vm.getPassword().setValue("abc12345");
        vm.next();
        assertEquals("该账号已停用", last(toasts));
        assertEquals(RecoverAccountViewModel.STEP_VERIFY, step());
    }

    // ---------- 无法验证身份 ----------

    @Test
    public void cannotVerify_showsUnverifiableStateAndBackReturns() {
        List<Void> toRegister = TestEnv.record(vm.getNavigateRegister());
        List<Void> toLogin = TestEnv.record(vm.getNavigateLogin());
        vm.cannotVerify();
        assertEquals(RecoverAccountViewModel.STEP_UNVERIFIABLE, step());
        assertEquals(0, env.api.recoverVerifyCount);

        vm.goRegister();
        vm.goLogin();
        assertEquals(1, toRegister.size());
        assertEquals(1, toLogin.size());

        assertTrue(vm.back());
        assertEquals(RecoverAccountViewModel.STEP_VERIFY, step());
        assertFalse(vm.back());   // 第 1 步由页面直接关闭
    }

    // ---------- 第 2 步：验证新手机号 ----------

    @Test
    public void sendCode_rebindSceneToNewPhone() {
        passByPassword();
        assertEquals(AccountTexts.REBIND_CODE_HINT, vm.getCodeHint().getValue());
        vm.getNewPhone().setValue("139 0000 1111");
        vm.sendCode();
        assertEquals("REBIND", env.api.lastSms.getScene());
        assertEquals("13900001111", env.api.lastSms.getPhone());
        assertEquals("验证码已发送至 139****1111，5 分钟内有效", vm.getCodeHint().getValue());
        assertEquals("60s 后重发", vm.getCountdownText().getValue());
    }

    @Test
    public void samePhone_blocksSendAndSubmit() {
        passByPassword();
        vm.getNewPhone().setValue("13800005678");
        vm.getCode().setValue("123456");
        assertEquals(AccountTexts.SAME_PHONE, vm.getNewPhoneError().getValue());
        assertFalse(vm.getCanSendCode().getValue());
        assertFalse(vm.getCanSubmit().getValue());
        vm.sendCode();
        assertEquals(0, env.api.sendSmsCount);
    }

    @Test
    public void sendCode_newPhoneRegistered_showsBelowNewPhone() {
        passByPassword();
        env.api.sendSmsResult = FakeAuthApi.fail(ApiException.PHONE_REGISTERED, "该手机号已注册");
        vm.getNewPhone().setValue("13900001111");
        vm.sendCode();
        assertEquals(AccountTexts.PHONE_REGISTERED_REBIND, vm.getNewPhoneError().getValue());
        assertFalse(vm.getCanSendCode().getValue());
        vm.getNewPhone().setValue("13900001112");
        assertNull(vm.getNewPhoneError().getValue());
        assertTrue(vm.getCanSendCode().getValue());
    }

    @Test
    public void submit_password_success_savesLoginAndShowsDone() {
        passByPassword();
        fillNewPhoneAndCode();
        vm.submit();
        assertEquals(1, env.api.recoverRebindCount);
        assertEquals("13800005678", env.api.lastRecoverRebind.getOldPhone());
        assertEquals("5d1e7a3c9b2f4e608d1e7a3c9b2f4e60", env.api.lastRecoverRebind.getRebindTicket());
        assertEquals("13900001111", env.api.lastRecoverRebind.getNewPhone());
        assertEquals("123456", env.api.lastRecoverRebind.getCode());
        assertNull(env.api.lastRecoverRebind.getNewPassword());   // 密码方式不改密码

        // 已用 saveLogin 保存新令牌（自动登录）
        assertEquals("token-new", env.token());
        assertTrue(env.session.isLoggedIn());
        assertEquals("13900001111", env.session.getUser().getPhone());

        assertEquals(RecoverAccountViewModel.STEP_DONE, step());
        assertEquals("今后请使用 139****1111 登录，已为你自动登录", vm.getSuccessDesc().getValue());
        assertFalse(vm.getRecoveryCodeUsed().getValue());
        assertEquals("", vm.getPassword().getValue());   // 不再保留密码

        vm.goMain();
        assertEquals(1, toMain.size());
        assertTrue(vm.back());                           // 成功态返回键等同「进入首页」
        assertEquals(2, toMain.size());
    }

    @Test
    public void submit_recoveryCode_sendsNewPasswordAndMentionsCodeUsed() {
        passByRecoveryCode();
        fillNewPhoneAndCode();
        vm.getNewPassword().setValue("abc12345");
        vm.getConfirm().setValue("abc12345");
        vm.submit();
        assertEquals("abc12345", env.api.lastRecoverRebind.getNewPassword());
        assertEquals(RecoverAccountViewModel.STEP_DONE, step());
        assertTrue(vm.getRecoveryCodeUsed().getValue());
        assertEquals("", vm.getNewPassword().getValue());
        assertEquals("", vm.getRecoveryCode().getValue());
        assertEquals("token-new", env.token());
    }

    @Test
    public void submit_recoveryCode_withoutNewPassword_doesNothing() {
        passByRecoveryCode();
        fillNewPhoneAndCode();
        vm.submit();
        assertEquals(0, env.api.recoverRebindCount);
    }

    @Test
    public void submit_wrongCode_clearsCodeAndShowsBelowCode() {
        passByPassword();
        fillNewPhoneAndCode();
        env.api.recoverRebindResult = FakeAuthApi.fail(ApiException.SMS_CODE_INVALID, "验证码错误或已过期");
        vm.submit();
        assertEquals("", vm.getCode().getValue());
        assertEquals(AccountTexts.CODE_EXPIRED, vm.getCodeHint().getValue());
        assertTrue(vm.getCodeHintError().getValue());
        assertEquals(RecoverAccountViewModel.STEP_NEW_PHONE, step());
        assertNull(env.token());

        vm.getCode().setValue("6");
        assertFalse(vm.getCodeHintError().getValue());
    }

    @Test
    public void submit_newPhoneRegistered_showsBelowNewPhone() {
        passByPassword();
        fillNewPhoneAndCode();
        env.api.recoverRebindResult = FakeAuthApi.fail(ApiException.PHONE_REGISTERED, "该手机号已注册");
        vm.submit();
        assertEquals(AccountTexts.PHONE_REGISTERED_REBIND, vm.getNewPhoneError().getValue());
        assertFalse(vm.getCanSubmit().getValue());
        assertEquals(RecoverAccountViewModel.STEP_NEW_PHONE, step());
    }

    @Test
    public void submit_ticketExpiredOnServer_returnsToStepOneKeepingInput() {
        passByPassword();
        fillNewPhoneAndCode();
        env.api.recoverRebindResult = FakeAuthApi.fail(ApiException.PARAM_ERROR, AccountTexts.REBIND_EXPIRED);
        vm.submit();
        assertEquals(RecoverAccountViewModel.STEP_VERIFY, step());
        assertEquals(AccountTexts.REBIND_EXPIRED, last(toasts));
        assertEquals("138 0000 5678", vm.getOldPhone().getValue());
        assertEquals("abc12345", vm.getPassword().getValue());
        assertEquals("139 0000 1111", vm.getNewPhone().getValue());

        // 重新验证后可以再次提交
        env.api.recoverRebindResult = FakeAuthApi.ok(FakeAuthApi.changedLogin("13900001111"));
        rx.passThrottle();
        vm.next();
        assertEquals(2, env.api.recoverVerifyCount);
        rx.passThrottle();
        vm.submit();
        assertEquals(RecoverAccountViewModel.STEP_DONE, step());
    }

    @Test
    public void submit_otherParamError_staysWithSnackbar() {
        passByPassword();
        fillNewPhoneAndCode();
        env.api.recoverRebindResult = FakeAuthApi.fail(ApiException.PARAM_ERROR, "新手机号不能与原手机号相同");
        vm.submit();
        assertEquals(RecoverAccountViewModel.STEP_NEW_PHONE, step());
        assertEquals("新手机号不能与原手机号相同", last(toasts));
    }

    @Test
    public void submit_ticketExpiredLocally_returnsToStepOneWithoutRequest() {
        long[] now = {0};
        vm.setClock(() -> now[0]);
        passByPassword();
        fillNewPhoneAndCode();
        now[0] = 600_000;   // 已超过 10 分钟（减去余量）
        vm.submit();
        assertEquals(0, env.api.recoverRebindCount);
        assertEquals(RecoverAccountViewModel.STEP_VERIFY, step());
        assertEquals(AccountTexts.REBIND_EXPIRED, last(toasts));
    }

    @Test
    public void backFromStepTwo_discardsTicketAndRequiresVerifyAgain() {
        passByPassword();
        assertTrue(vm.back());
        assertEquals(RecoverAccountViewModel.STEP_VERIFY, step());
        assertEquals("138 0000 5678", vm.getOldPhone().getValue());

        rx.passThrottle();
        vm.next();
        assertEquals(2, env.api.recoverVerifyCount);
        assertEquals(RecoverAccountViewModel.STEP_NEW_PHONE, step());
    }

    @Test
    public void ticketExpiredMessage_matchesPrefix() {
        assertTrue(RecoverAccountViewModel.isTicketExpiredMessage("验证已过期，请重新验证身份"));
        assertFalse(RecoverAccountViewModel.isTicketExpiredMessage("参数错误"));
        assertFalse(RecoverAccountViewModel.isTicketExpiredMessage(null));
    }
}
