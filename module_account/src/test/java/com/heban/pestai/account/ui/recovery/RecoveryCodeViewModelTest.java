package com.heban.pestai.account.ui.recovery;

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

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

public class RecoveryCodeViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private RecoveryCodeViewModel vm;
    private List<String> toasts;
    private List<Void> confirms;
    private List<Void> leaveConfirms;
    private List<String> copies;
    private List<Void> finishes;
    private List<Void> goMains;

    @Before
    public void setUp() {
        env = new TestEnv().withLogin();
    }

    private static Date dateOf(int year, int month, int day) {
        return Date.from(LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static String last(List<String> list) {
        return list.get(list.size() - 1);
    }

    /** 服务器状态需在创建前设置：构造时即查询 */
    private void create() {
        vm = new RecoveryCodeViewModel(env.repository);
        toasts = TestEnv.record(vm.getToast());
        confirms = TestEnv.record(vm.getShowRegenerateConfirm());
        leaveConfirms = TestEnv.record(vm.getShowLeaveConfirm());
        copies = TestEnv.record(vm.getCopyCode());
        finishes = TestEnv.record(vm.getFinish());
        goMains = TestEnv.record(vm.getGoMain());
        TestEnv.record(vm.getStatusText());
        TestEnv.record(vm.getGenerated());
        TestEnv.record(vm.getNeedCode());
        TestEnv.record(vm.getDirect());
        TestEnv.record(vm.getCanSendCode());
        TestEnv.record(vm.getCodeHint());
        TestEnv.record(vm.getCodeHintError());
        TestEnv.record(vm.getCanGenerate());
        TestEnv.record(vm.getShowResult());
    }

    /** 引导模式（A06 之后，Intent 参数 from_register=true） */
    private void createGuide() {
        create();
        vm.init(true);
    }

    /** 10 分钟内刚通过短信验证：verify_required=false */
    private void verifiedRecently(boolean generated, Date createdAt) {
        env.api.recoveryCodeStatusResult = FakeAuthApi.ok(FakeAuthApi.recoveryStatus(generated, createdAt, false));
    }

    /** 生成恢复码（未生成状态，不需要确认） */
    private void generateFresh() {
        create();
        vm.getCode().setValue("123456");
        vm.generate();
        assertTrue(vm.getShowResult().getValue());
    }

    // ---------- 状态 ----------

    @Test
    public void status_notGenerated() {
        create();
        assertEquals(1, env.api.recoveryCodeStatusCount);
        assertEquals("当前状态：未生成", vm.getStatusText().getValue());
        assertFalse(vm.getGenerated().getValue());
        assertEquals("验证当前手机号 138****5678", vm.getVerifyTitle());
        assertFalse(vm.getShowResult().getValue());
        assertFalse(vm.getGuide().getValue());
        // verify_required=true：显示验证码区域
        assertTrue(vm.getNeedCode().getValue());
        assertFalse(vm.getDirect().getValue());
    }

    @Test
    public void status_loading_hidesBothAreasAndCannotGenerate() {
        env.api.recoveryCodeStatusResult = Single.never();
        create();
        assertEquals(AccountTexts.RECOVERY_STATUS_LOADING, vm.getStatusText().getValue());
        assertFalse(vm.getNeedCode().getValue());
        assertFalse(vm.getDirect().getValue());
        vm.getCode().setValue("123456");
        assertFalse(vm.getCanGenerate().getValue());
        vm.generate();
        assertEquals(0, env.api.generateRecoveryCodeCount);
        assertTrue(confirms.isEmpty());
    }

    @Test
    public void status_failure_fallsBackToCodeArea() {
        env.api.recoveryCodeStatusResult = FakeAuthApi.fail(ApiException.SERVER_ERROR, null);
        create();
        assertTrue(vm.getNeedCode().getValue());
        assertFalse(vm.getDirect().getValue());
    }

    @Test
    public void status_generated_showsDate() {
        env.api.recoveryCodeStatusResult = FakeAuthApi.ok(FakeAuthApi.recoveryStatus(true, dateOf(2026, 10, 13)));
        create();
        assertEquals("当前状态：已生成（2026年10月13日）", vm.getStatusText().getValue());
        assertTrue(vm.getGenerated().getValue());
    }

    @Test
    public void status_failure_showsUnknownAndSnackbar() {
        env.api.recoveryCodeStatusResult = FakeAuthApi.fail(ApiException.SERVER_ERROR, null);
        create();
        assertEquals(AccountTexts.RECOVERY_STATUS_UNKNOWN, vm.getStatusText().getValue());
        assertEquals("服务器开小差了，请稍后再试", last(toasts));
        assertFalse(vm.getGenerated().getValue());
    }

    // ---------- 发码 ----------

    @Test
    public void sendCode_toCurrentPhone() {
        create();
        assertTrue(vm.getCanSendCode().getValue());
        vm.sendCode();
        assertEquals(1, env.api.recoveryCodeSmsCount);
        assertEquals(0, env.api.sendSmsCount);   // 不走 auth/sms/send
        assertEquals("验证码已发送至 138****5678，5 分钟内有效", vm.getCodeHint().getValue());
        assertEquals("60s 后重发", vm.getCountdownText().getValue());
        assertFalse(vm.getCanSendCode().getValue());
    }

    @Test
    public void sendCode_tooFrequent_showsSnackbar() {
        env.api.recoveryCodeSmsResult = FakeAuthApi.fail(ApiException.TOO_FREQUENT, null);
        create();
        vm.sendCode();
        assertEquals("操作太频繁，请稍后再试", last(toasts));
        assertTrue(vm.getCanSendCode().getValue());
    }

    // ---------- 生成 ----------

    @Test
    public void canGenerate_requiresSixDigitCode() {
        create();
        assertFalse(vm.getCanGenerate().getValue());
        vm.getCode().setValue("12345");
        assertFalse(vm.getCanGenerate().getValue());
        vm.getCode().setValue("123456");
        assertTrue(vm.getCanGenerate().getValue());
        vm.getCode().setValue("");
        vm.generate();
        assertEquals(0, env.api.generateRecoveryCodeCount);
    }

    @Test
    public void generate_notGenerated_noConfirmAndShowsResult() {
        env.api.generateRecoveryCodeResult =
                FakeAuthApi.ok(FakeAuthApi.recoveryGenerated("abcdefghjklm", dateOf(2026, 10, 13)));
        generateFresh();
        assertTrue(confirms.isEmpty());
        assertEquals(1, env.api.generateRecoveryCodeCount);
        assertEquals("123456", env.api.lastGenerateRecoveryCode.getCode());
        assertEquals("ABCD-EFGH-JKLM", vm.getRecoveryCode().getValue());   // 统一为大写 + 连字符
        assertEquals("当前状态：已生成（2026年10月13日）", vm.getStatusText().getValue());
        assertTrue(vm.getGenerated().getValue());
        assertFalse(vm.getCanGenerate().getValue());
        assertFalse(vm.getSaved().getValue());
        assertEquals("", vm.getCode().getValue());
    }

    @Test
    public void generate_alreadyGenerated_confirmsFirst() {
        env.api.recoveryCodeStatusResult = FakeAuthApi.ok(FakeAuthApi.recoveryStatus(true, dateOf(2026, 1, 2)));
        create();
        vm.getCode().setValue("123456");
        vm.generate();
        assertEquals(1, confirms.size());
        assertEquals(0, env.api.generateRecoveryCodeCount);

        vm.confirmRegenerate();
        assertEquals(1, env.api.generateRecoveryCodeCount);
        assertEquals("ABCD-EFGH-JKLM", vm.getRecoveryCode().getValue());
    }

    @Test
    public void generate_statusUnknown_confirmsFirst() {
        env.api.recoveryCodeStatusResult = FakeAuthApi.fail(ApiException.NETWORK, null);
        create();
        vm.getCode().setValue("123456");
        vm.generate();
        assertEquals(1, confirms.size());
        assertEquals(0, env.api.generateRecoveryCodeCount);
    }

    @Test
    public void generate_wrongCode_clearsCodeAndShowsBelowCode() {
        env.api.generateRecoveryCodeResult = FakeAuthApi.fail(ApiException.SMS_CODE_INVALID, "验证码错误或已过期");
        create();
        vm.getCode().setValue("123456");
        vm.generate();
        assertEquals("", vm.getCode().getValue());
        assertEquals(AccountTexts.CODE_EXPIRED, vm.getCodeHint().getValue());
        assertTrue(vm.getCodeHintError().getValue());
        assertFalse(vm.getShowResult().getValue());
        assertEquals("当前状态：未生成", vm.getStatusText().getValue());

        vm.getCode().setValue("6");
        assertFalse(vm.getCodeHintError().getValue());
    }

    // ---------- 复制与保存确认 ----------

    @Test
    public void copy_emitsCodeAndSnackbar() {
        create();
        vm.copy();                      // 还没有结果：不响应
        assertTrue(copies.isEmpty());

        vm.getCode().setValue("123456");
        vm.generate();
        vm.copy();
        assertEquals(1, copies.size());
        assertEquals("ABCD-EFGH-JKLM", copies.get(0));
        assertEquals(AccountTexts.RECOVERY_COPIED, last(toasts));
    }

    @Test
    public void done_requiresSavedCheckbox() {
        generateFresh();
        vm.done();
        assertTrue(finishes.isEmpty());
        vm.getSaved().setValue(true);
        vm.done();
        assertEquals(1, finishes.size());
    }

    @Test
    public void back_withUnsavedResult_remindsFirst() {
        generateFresh();
        vm.back();
        assertEquals(1, leaveConfirms.size());
        assertTrue(finishes.isEmpty());

        vm.leave();
        assertEquals(1, finishes.size());
    }

    @Test
    public void back_afterSaved_orBeforeResult_finishesDirectly() {
        create();
        vm.back();
        assertEquals(1, finishes.size());
        assertTrue(leaveConfirms.isEmpty());

        vm.getCode().setValue("123456");
        vm.generate();
        vm.getSaved().setValue(true);
        vm.back();
        assertEquals(2, finishes.size());
        assertTrue(leaveConfirms.isEmpty());
    }

    @Test
    public void normalMode_leavingFinishesWithoutGoMain() {
        generateFresh();
        vm.getSaved().setValue(true);
        vm.done();
        assertEquals(1, finishes.size());
        assertTrue(goMains.isEmpty());
    }

    // ---------- 免验证（10 分钟内刚通过短信验证） ----------

    @Test
    public void direct_hidesCodeAreaAndGeneratesWithoutCode() {
        verifiedRecently(false, null);
        env.api.generateRecoveryCodeResult =
                FakeAuthApi.ok(FakeAuthApi.recoveryGenerated("abcdefghjklm", dateOf(2026, 10, 13)));
        create();
        assertTrue(vm.getDirect().getValue());
        assertFalse(vm.getNeedCode().getValue());
        assertTrue(vm.getCanGenerate().getValue());   // 不需要输入验证码

        vm.generate();
        assertTrue(confirms.isEmpty());
        assertEquals(1, env.api.generateRecoveryCodeCount);
        assertNull(env.api.lastGenerateRecoveryCode.getCode());
        assertEquals("{}", env.gson.toJson(env.api.lastGenerateRecoveryCode));   // 请求体不带 code
        assertEquals(0, env.api.recoveryCodeSmsCount);
        assertEquals("ABCD-EFGH-JKLM", vm.getRecoveryCode().getValue());
        assertEquals("当前状态：已生成（2026年10月13日）", vm.getStatusText().getValue());
    }

    @Test
    public void direct_alreadyGenerated_stillConfirmsFirst() {
        verifiedRecently(true, dateOf(2026, 1, 2));
        create();
        assertTrue(vm.getGenerated().getValue());
        vm.generate();
        assertEquals(1, confirms.size());
        assertEquals(0, env.api.generateRecoveryCodeCount);

        vm.confirmRegenerate();
        assertEquals(1, env.api.generateRecoveryCodeCount);
        assertNull(env.api.lastGenerateRecoveryCode.getCode());
        assertTrue(vm.getShowResult().getValue());
    }

    @Test
    public void direct_windowExpired_switchesToCodeAreaAndPrompts() {
        verifiedRecently(false, null);
        env.api.generateRecoveryCodeResult = FakeAuthApi.fail(ApiException.PARAM_ERROR, "请先验证当前手机号");
        create();
        vm.generate();

        assertEquals(1, env.api.generateRecoveryCodeCount);
        assertNull(env.api.lastGenerateRecoveryCode.getCode());
        assertTrue(vm.getNeedCode().getValue());
        assertFalse(vm.getDirect().getValue());
        assertFalse(vm.getCanGenerate().getValue());   // 需要先输入验证码
        assertFalse(vm.getShowResult().getValue());
        assertEquals(AccountTexts.RECOVERY_VERIFY_EXPIRED, last(toasts));

        // 之后按原流程：获取验证码 → 输入 → 生成（带 code）
        env.api.generateRecoveryCodeResult = FakeAuthApi.ok(FakeAuthApi.recoveryGenerated("ABCD-EFGH-JKLM", new Date()));
        vm.sendCode();
        assertEquals(1, env.api.recoveryCodeSmsCount);
        vm.getCode().setValue("654321");
        assertTrue(vm.getCanGenerate().getValue());
        rx.passThrottle();
        vm.generate();
        assertEquals(2, env.api.generateRecoveryCodeCount);
        assertEquals("654321", env.api.lastGenerateRecoveryCode.getCode());
        assertTrue(vm.getShowResult().getValue());
    }

    @Test
    public void direct_networkFailure_staysDirectAndShowsSnackbar() {
        verifiedRecently(false, null);
        env.api.generateRecoveryCodeResult = Single.error(new IOException("offline"));
        create();
        vm.generate();
        assertTrue(vm.getDirect().getValue());
        assertTrue(vm.getCanGenerate().getValue());
        assertEquals(ApiException.defaultMessage(ApiException.NETWORK), last(toasts));
        assertNull(vm.getGenerateError().getValue());   // 普通模式不在页面内显示
    }

    // ---------- 引导模式（from_register） ----------

    @Test
    public void guide_init_onlyOnce() {
        createGuide();
        vm.init(false);
        assertTrue(vm.getGuide().getValue());
    }

    @Test
    public void guide_later_goesMainWithoutRequest() {
        createGuide();
        vm.later();
        assertEquals(1, goMains.size());
        assertTrue(finishes.isEmpty());
        assertEquals(0, env.api.generateRecoveryCodeCount);
    }

    @Test
    public void guide_backBeforeResult_sameAsLater() {
        createGuide();
        vm.back();
        assertEquals(1, goMains.size());
        assertTrue(leaveConfirms.isEmpty());
        assertTrue(finishes.isEmpty());
    }

    @Test
    public void guide_laterIgnoredWhileGenerating() {
        verifiedRecently(false, null);
        env.api.generateRecoveryCodeResult = Single.never();
        createGuide();
        vm.generate();
        assertTrue(vm.getGenerating().getValue());
        vm.later();
        assertTrue(goMains.isEmpty());
    }

    @Test
    public void guide_generateThenDone_goesMain() {
        verifiedRecently(false, null);
        createGuide();
        vm.generate();
        assertTrue(vm.getShowResult().getValue());

        vm.done();                       // 未勾选：不可完成
        assertTrue(goMains.isEmpty());
        vm.getSaved().setValue(true);
        vm.done();
        assertEquals(1, goMains.size());
        assertTrue(finishes.isEmpty());
    }

    @Test
    public void guide_backWithUnsavedResult_remindsThenGoesMain() {
        verifiedRecently(false, null);
        createGuide();
        vm.generate();

        vm.back();
        assertEquals(1, leaveConfirms.size());
        assertTrue(goMains.isEmpty());
        vm.leave();
        assertEquals(1, goMains.size());
        assertTrue(finishes.isEmpty());
    }

    @Test
    public void guide_statusUnknown_generatesWithoutConfirm() {
        env.api.recoveryCodeStatusResult = FakeAuthApi.fail(ApiException.NETWORK, null);
        createGuide();
        assertTrue(vm.getNeedCode().getValue());
        vm.getCode().setValue("123456");
        vm.generate();
        assertTrue(confirms.isEmpty());   // 账号刚创建，不可能已有恢复码
        assertEquals(1, env.api.generateRecoveryCodeCount);
        assertEquals("123456", env.api.lastGenerateRecoveryCode.getCode());
    }

    @Test
    public void guide_generateFailure_showsReasonAndRetry() {
        verifiedRecently(false, null);
        env.api.generateRecoveryCodeResult = Single.error(new IOException("offline"));
        createGuide();
        List<String> errors = TestEnv.record(vm.getGenerateError());
        vm.generate();

        assertEquals(ApiException.defaultMessage(ApiException.NETWORK), vm.getGenerateError().getValue());
        assertTrue(toasts.isEmpty());                  // 原因显示在页面内，不再弹 Snackbar
        assertTrue(vm.getCanGenerate().getValue());    // 按钮变为「重试」
        assertFalse(vm.getShowResult().getValue());

        env.api.generateRecoveryCodeResult = FakeAuthApi.ok(FakeAuthApi.recoveryGenerated("ABCD-EFGH-JKLM", new Date()));
        rx.passThrottle();
        vm.generate();
        assertNull(vm.getGenerateError().getValue());
        assertTrue(vm.getShowResult().getValue());
        assertEquals(2, env.api.generateRecoveryCodeCount);
        assertTrue(errors.contains(ApiException.defaultMessage(ApiException.NETWORK)));
    }

    @Test
    public void guide_windowExpired_switchesToCodeAreaInsteadOfRetry() {
        verifiedRecently(false, null);
        env.api.generateRecoveryCodeResult = FakeAuthApi.fail(ApiException.PARAM_ERROR, "请先验证当前手机号");
        createGuide();
        vm.generate();
        assertTrue(vm.getNeedCode().getValue());
        assertNull(vm.getGenerateError().getValue());
        assertEquals(AccountTexts.RECOVERY_VERIFY_EXPIRED, last(toasts));
    }

    @Test
    public void toLocalDate_nullSafe() {
        assertNull(RecoveryCodeViewModel.toLocalDate(null));
        assertEquals(LocalDate.of(2026, 10, 13), RecoveryCodeViewModel.toLocalDate(dateOf(2026, 10, 13)));
    }
}
