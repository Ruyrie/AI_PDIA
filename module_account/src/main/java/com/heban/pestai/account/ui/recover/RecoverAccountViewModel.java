package com.heban.pestai.account.ui.recover;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.account.util.PasswordRules;
import com.heban.pestai.account.util.PhoneRules;
import com.heban.pestai.account.util.RecoveryCodeRules;
import com.heban.pestai.account.util.SmsCountdown;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.constant.RecoverMethod;
import com.heban.pestai.common.constant.SmsScene;
import com.heban.pestai.common.vo.RebindTicketVO;
import com.heban.pestai.network.ApiException;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A09 找回账号：原手机号不可用时，只用本人才知道的信息自助验证身份，再验证新手机号后立即换绑并自动登录。
 * <ol>
 *     <li>验证身份：原手机号 + 登录密码 / 账号恢复码 → auth/recover/verify 换取 10 分钟有效的一次性 rebind_ticket；</li>
 *     <li>验证新手机号：REBIND 验证码；第 1 步用的是恢复码时必须同时设置新密码（恢复码使用后失效）→ auth/recover/rebind；</li>
 *     <li>成功：仓库已用 UserSession.saveLogin() 保存新令牌，页面显示成功态，「进入首页」清空任务栈进入 RouterPath.MAIN。</li>
 * </ol>
 * 密码和恢复码都不记得时显示「无法验证身份」状态（{@link #STEP_UNVERIFIABLE}），引导用其他设备更换手机号或注册新账号。
 * 页面不收集昵称、地区、最近登录时间等无法核实的信息。
 * <p>错误显示位置：40104 原手机号下方；40102 密码下方；40105 恢复码下方；40901 新手机号下方；40103 验证码下方；
 * 凭证过期（本地截止时刻已过，或 40001「验证已过期…」）回到第 1 步并提示。</p>
 */
public class RecoverAccountViewModel extends BaseViewModel {

    public static final int STEP_VERIFY = 1;
    public static final int STEP_NEW_PHONE = 2;
    public static final int STEP_DONE = 3;
    public static final int STEP_UNVERIFIABLE = 4;
    /** 发送验证码防连点窗口（与 A07 / A10 一致：1 秒） */
    public static final long SEND_WINDOW_MS = 1000;
    public static final long CLICK_WINDOW_MS = 500;
    /** 本地截止时刻预留的余量（抵消网络传输与操作耗时） */
    public static final long DEADLINE_MARGIN_MS = 30_000;

    private final AccountRepository repository;

    private final MutableLiveData<Integer> step = new MutableLiveData<>(STEP_VERIFY);

    // ---------- 第 1 步：验证身份 ----------
    private final MutableLiveData<String> oldPhone = new MutableLiveData<>("");
    /** 服务器针对原手机号的错误（40104），只在输入框中仍是该号码时显示 */
    private final MutableLiveData<FieldError> oldPhoneServerError = new MutableLiveData<>(null);
    private final MediatorLiveData<String> oldPhoneError = new MediatorLiveData<>(null);
    private final MutableLiveData<String> method = new MutableLiveData<>(RecoverMethod.PASSWORD);
    private final LiveData<Boolean> passwordMethod;
    private final MutableLiveData<String> password = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> passwordVisible = new MutableLiveData<>(false);
    /** 40102，修改密码后清除 */
    private final MutableLiveData<String> passwordError = new MutableLiveData<>(null);
    private final MutableLiveData<String> recoveryCode = new MutableLiveData<>("");
    /** 40105，修改恢复码后清除 */
    private final MutableLiveData<String> recoveryCodeServerError = new MutableLiveData<>(null);
    private final MediatorLiveData<String> recoveryCodeHint = new MediatorLiveData<>(AccountTexts.RECOVERY_CODE_HINT);
    private final MediatorLiveData<Boolean> recoveryCodeHintError = new MediatorLiveData<>(false);
    private final MutableLiveData<Boolean> verifying = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canNext = new MediatorLiveData<>(false);

    // ---------- 第 2 步：验证新手机号 ----------
    /** 通过第 1 步时使用的验证方式；恢复码方式必须设置新密码 */
    private final MutableLiveData<String> verifiedMethod = new MutableLiveData<>(null);
    private final LiveData<Boolean> needNewPassword;
    private final MutableLiveData<String> newPhone = new MutableLiveData<>("");
    /** 服务器针对新手机号的错误（40901），只在输入框中仍是该号码时显示 */
    private final MutableLiveData<FieldError> newPhoneServerError = new MutableLiveData<>(null);
    private final MediatorLiveData<String> newPhoneError = new MediatorLiveData<>(null);
    private final MutableLiveData<String> code = new MutableLiveData<>("");
    private final SmsCountdown countdown = new SmsCountdown();
    private final MutableLiveData<Boolean> sendingCode = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSendCode = new MediatorLiveData<>(false);
    /** 最近一次成功发送验证码的新手机号（去空格） */
    private final MutableLiveData<String> sentPhone = new MutableLiveData<>(null);
    /** 提交返回 40103 后的验证码错误提示，修改验证码即清除 */
    private final MutableLiveData<String> codeError = new MutableLiveData<>(null);
    private final MediatorLiveData<String> codeHint = new MediatorLiveData<>(AccountTexts.REBIND_CODE_HINT);
    private final MediatorLiveData<Boolean> codeHintError = new MediatorLiveData<>(false);
    private final MutableLiveData<String> newPassword = new MutableLiveData<>("");
    private final MutableLiveData<String> confirm = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> newPasswordVisible = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> confirmVisible = new MutableLiveData<>(false);
    private final LiveData<Integer> strengthLevel;
    private final LiveData<Boolean> ruleLength;
    private final LiveData<Boolean> ruleMix;
    private final LiveData<String> newPasswordError;
    /** 确认密码下方：一致时「两次输入一致」（primary），不一致时错误提示 */
    private final MediatorLiveData<String> confirmHint = new MediatorLiveData<>(null);
    private final MediatorLiveData<Boolean> confirmHintError = new MediatorLiveData<>(false);
    private final MutableLiveData<Boolean> submitting = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSubmit = new MediatorLiveData<>(false);

    // ---------- 成功态 ----------
    private final MutableLiveData<String> successDesc = new MutableLiveData<>("");
    /** 用恢复码找回：成功态再提示「恢复码已使用并失效…」 */
    private final MutableLiveData<Boolean> recoveryCodeUsed = new MutableLiveData<>(false);

    private final SingleLiveEvent<Void> focusCode = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> navigateMain = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> navigateLogin = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> navigateRegister = new SingleLiveEvent<>();
    private final PublishSubject<Boolean> nextClicks = PublishSubject.create();
    private final PublishSubject<Boolean> sendClicks = PublishSubject.create();
    private final PublishSubject<Boolean> submitClicks = PublishSubject.create();

    private LongSupplier clock = SystemClock::elapsedRealtime;
    @Nullable
    private String rebindTicket;
    private long ticketDeadline;

    @Inject
    public RecoverAccountViewModel(AccountRepository repository) {
        this.repository = repository;
        passwordMethod = Transformations.map(method, m -> !RecoverMethod.RECOVERY_CODE.equals(m));
        needNewPassword = Transformations.map(verifiedMethod, RecoverMethod.RECOVERY_CODE::equals);
        strengthLevel = Transformations.map(newPassword, PasswordRules::level);
        ruleLength = Transformations.map(newPassword, PasswordRules::lengthOk);
        ruleMix = Transformations.map(newPassword, PasswordRules::mixOk);
        newPasswordError = Transformations.map(newPassword, AccountTexts::passwordError);

        // 第 1 步
        oldPhoneError.addSource(oldPhone, v -> updateOldPhoneError());
        oldPhoneError.addSource(oldPhoneServerError, v -> updateOldPhoneError());
        recoveryCodeHint.addSource(recoveryCode, v -> {
            if (recoveryCodeServerError.getValue() != null) {
                recoveryCodeServerError.setValue(null);
            }
            updateRecoveryCodeHint();
        });
        recoveryCodeHint.addSource(recoveryCodeServerError, v -> updateRecoveryCodeHint());
        canNext.addSource(oldPhone, v -> updateCanNext());
        canNext.addSource(oldPhoneServerError, v -> updateCanNext());
        canNext.addSource(method, v -> updateCanNext());
        canNext.addSource(password, v -> {
            if (passwordError.getValue() != null) {
                passwordError.setValue(null);
            }
            updateCanNext();
        });
        canNext.addSource(recoveryCode, v -> updateCanNext());
        canNext.addSource(verifying, v -> updateCanNext());

        // 第 2 步
        newPhoneError.addSource(newPhone, v -> updateNewPhoneError());
        newPhoneError.addSource(newPhoneServerError, v -> updateNewPhoneError());
        canSendCode.addSource(newPhone, v -> updateCanSendCode());
        canSendCode.addSource(newPhoneServerError, v -> updateCanSendCode());
        canSendCode.addSource(countdown.getCounting(), v -> updateCanSendCode());
        canSendCode.addSource(sendingCode, v -> updateCanSendCode());
        codeHint.addSource(newPhone, v -> updateCodeHint());
        codeHint.addSource(sentPhone, v -> updateCodeHint());
        codeHint.addSource(codeError, v -> updateCodeHint());
        confirmHint.addSource(newPassword, v -> updateConfirmHint());
        confirmHint.addSource(confirm, v -> updateConfirmHint());
        canSubmit.addSource(newPhone, v -> updateCanSubmit());
        canSubmit.addSource(newPhoneServerError, v -> updateCanSubmit());
        canSubmit.addSource(code, v -> {
            if (codeError.getValue() != null) {
                codeError.setValue(null);
            }
            updateCanSubmit();
        });
        canSubmit.addSource(verifiedMethod, v -> updateCanSubmit());
        canSubmit.addSource(newPassword, v -> updateCanSubmit());
        canSubmit.addSource(confirm, v -> updateCanSubmit());
        canSubmit.addSource(submitting, v -> updateCanSubmit());

        add(nextClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doNext()));
        add(sendClicks.throttleFirst(SEND_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSendCode()));
        add(submitClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSubmit()));
    }

    // ---------- 步骤 ----------

    /**
     * 返回键 / 顶栏返回：第 2 步与「无法验证身份」回到第 1 步（已填内容保留，换绑凭证作废，需重新验证）；
     * 成功态等同「进入首页」。返回 false 表示已在第 1 步，由页面直接关闭。
     */
    public boolean back() {
        switch (currentStep()) {
            case STEP_NEW_PHONE:
                discardTicket();
                step.setValue(STEP_VERIFY);
                return true;
            case STEP_UNVERIFIABLE:
                step.setValue(STEP_VERIFY);
                return true;
            case STEP_DONE:
                goMain();
                return true;
            default:
                return false;
        }
    }

    /** 「密码和恢复码都忘了？」→ 无法验证身份 */
    public void cannotVerify() {
        if (currentStep() == STEP_VERIFY && !Boolean.TRUE.equals(verifying.getValue())) {
            step.setValue(STEP_UNVERIFIABLE);
        }
    }

    /** 「用新手机号注册」 */
    public void goRegister() {
        navigateRegister.call();
    }

    /** 「返回登录」 */
    public void goLogin() {
        navigateLogin.call();
    }

    /** 成功态「进入首页」与返回键共用 */
    public void goMain() {
        navigateMain.call();
    }

    // ---------- 第 1 步 ----------

    /** 验证方式分段按钮：{@link RecoverMethod#PASSWORD} / {@link RecoverMethod#RECOVERY_CODE} */
    public void selectMethod(@NonNull String value) {
        if (!value.equals(method.getValue())) {
            method.setValue(value);
        }
    }

    public void togglePasswordVisible() {
        passwordVisible.setValue(!Boolean.TRUE.equals(passwordVisible.getValue()));
    }

    /** 「下一步」：验证身份，换取 rebind_ticket */
    public void next() {
        nextClicks.onNext(Boolean.TRUE);
    }

    private void doNext() {
        if (currentStep() != STEP_VERIFY || Boolean.TRUE.equals(verifying.getValue()) || !isIdentityReady()) {
            return;
        }
        final String phone = PhoneRules.normalize(oldPhone.getValue());
        final String usedMethod = currentMethod();
        String secret = RecoverMethod.RECOVERY_CODE.equals(usedMethod) ? recoveryCode.getValue() : password.getValue();
        verifying.setValue(true);
        add(repository.verifyRecover(phone, usedMethod, secret)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> verifying.setValue(false))
                .subscribe(ticket -> onVerified(ticket, usedMethod), e -> onVerifyError(phone, usedMethod, e)));
    }

    private void onVerified(@NonNull RebindTicketVO ticket, @NonNull String usedMethod) {
        if (currentStep() != STEP_VERIFY) {
            return;
        }
        rebindTicket = ticket.getRebindTicket();
        ticketDeadline = clock.getAsLong() + ticket.getExpireIn() * 1000L - DEADLINE_MARGIN_MS;
        // 以服务器返回的 method 为准（决定换绑时是否必须设置新密码）
        String confirmed = ticket.getMethod() == null || ticket.getMethod().isEmpty() ? usedMethod : ticket.getMethod();
        verifiedMethod.setValue(confirmed);
        step.setValue(STEP_NEW_PHONE);
    }

    private void onVerifyError(@NonNull String phone, @NonNull String usedMethod, @NonNull Throwable e) {
        int errorCode = ApiException.codeOf(e);
        switch (errorCode) {
            case ApiException.NOT_REGISTERED:
                oldPhoneServerError.setValue(new FieldError(phone, AccountTexts.NOT_REGISTERED));
                break;
            case ApiException.WRONG_PASSWORD:
                if (RecoverMethod.PASSWORD.equals(usedMethod)) {
                    passwordError.setValue(AccountTexts.WRONG_PASSWORD);
                } else {
                    showToast(AccountTexts.WRONG_PASSWORD);
                }
                break;
            case ApiException.RECOVERY_CODE_INVALID:
                if (RecoverMethod.RECOVERY_CODE.equals(usedMethod)) {
                    recoveryCodeServerError.setValue(AccountTexts.RECOVERY_CODE_INVALID);
                } else {
                    showToast(AccountTexts.RECOVERY_CODE_INVALID);
                }
                break;
            case ApiException.ACCOUNT_DISABLED:
                showToast(AccountTexts.ACCOUNT_DISABLED);
                break;
            default:
                showToast(ApiException.messageOf(e));
                break;
        }
    }

    // ---------- 第 2 步 ----------

    /** 「获取验证码」：向新手机号发送 REBIND 场景验证码 */
    public void sendCode() {
        sendClicks.onNext(Boolean.TRUE);
    }

    /** 「完成换绑」 */
    public void submit() {
        submitClicks.onNext(Boolean.TRUE);
    }

    public void toggleNewPasswordVisible() {
        newPasswordVisible.setValue(!Boolean.TRUE.equals(newPasswordVisible.getValue()));
    }

    public void toggleConfirmVisible() {
        confirmVisible.setValue(!Boolean.TRUE.equals(confirmVisible.getValue()));
    }

    private void doSendCode() {
        if (countdown.isCounting() || Boolean.TRUE.equals(sendingCode.getValue())) {
            return;
        }
        final String target = PhoneRules.normalize(newPhone.getValue());
        if (!PhoneRules.isValid(target)) {
            showToast(AccountTexts.PHONE_INVALID);
            return;
        }
        if (!isNewPhoneUsable(target)) {
            return;
        }
        sendingCode.setValue(true);
        add(repository.sendCode(target, SmsScene.REBIND)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> sendingCode.setValue(false))
                .subscribe(() -> {
                    sentPhone.setValue(target);
                    countdown.start();
                    focusCode.call();
                }, e -> onNewPhoneError(target, e)));
    }

    private void doSubmit() {
        if (currentStep() != STEP_NEW_PHONE || Boolean.TRUE.equals(submitting.getValue()) || !isRebindReady()) {
            return;
        }
        if (rebindTicket == null || rebindTicket.isEmpty() || clock.getAsLong() > ticketDeadline) {
            expire();
            return;
        }
        final String target = PhoneRules.normalize(newPhone.getValue());
        final boolean byRecoveryCode = isVerifiedByRecoveryCode();
        String pwd = byRecoveryCode ? newPassword.getValue() : null;
        submitting.setValue(true);
        add(repository.rebind(oldPhone.getValue(), rebindTicket, target, code.getValue(), pwd)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> submitting.setValue(false))
                .subscribe(login -> onRebound(target, byRecoveryCode), e -> onRebindError(target, e)));
    }

    private void onRebound(@NonNull String target, boolean byRecoveryCode) {
        countdown.stop();
        discardTicket();
        // 密码已提交，不再保留在内存中
        password.setValue("");
        recoveryCode.setValue("");
        newPassword.setValue("");
        confirm.setValue("");
        successDesc.setValue(AccountTexts.rebindSuccess(target));
        recoveryCodeUsed.setValue(byRecoveryCode);
        step.setValue(STEP_DONE);
    }

    private void onRebindError(@NonNull String target, @NonNull Throwable e) {
        int errorCode = ApiException.codeOf(e);
        String message = ApiException.messageOf(e);
        if (errorCode == ApiException.SMS_CODE_INVALID) {
            code.setValue("");
            codeError.setValue(AccountTexts.CODE_EXPIRED);
        } else if (errorCode == ApiException.PHONE_REGISTERED) {
            newPhoneServerError.setValue(new FieldError(target, AccountTexts.PHONE_REGISTERED_REBIND));
        } else if (errorCode == ApiException.PARAM_ERROR && isTicketExpiredMessage(message)) {
            expire();
        } else {
            showToast(message);
        }
    }

    /** 发送验证码失败：40901 显示在新手机号下方，其余以 Snackbar 提示 */
    private void onNewPhoneError(@NonNull String target, @NonNull Throwable e) {
        if (ApiException.codeOf(e) == ApiException.PHONE_REGISTERED) {
            newPhoneServerError.setValue(new FieldError(target, AccountTexts.PHONE_REGISTERED_REBIND));
        } else {
            showToast(ApiException.messageOf(e));
        }
    }

    /** 凭证过期：回到第 1 步（已填内容保留），提示重新验证身份 */
    private void expire() {
        discardTicket();
        step.setValue(STEP_VERIFY);
        showToast(AccountTexts.REBIND_EXPIRED);
    }

    private void discardTicket() {
        rebindTicket = null;
        ticketDeadline = 0;
    }

    static boolean isTicketExpiredMessage(@Nullable String message) {
        return message != null && message.startsWith(AccountTexts.REBIND_EXPIRED_PREFIX);
    }

    // ---------- 校验 ----------

    @NonNull
    private String currentMethod() {
        String value = method.getValue();
        return RecoverMethod.RECOVERY_CODE.equals(value) ? RecoverMethod.RECOVERY_CODE : RecoverMethod.PASSWORD;
    }

    /** 服务器针对某个号码的错误；号码已修改时为 null */
    @Nullable
    private static String errorFor(@Nullable FieldError error, @Nullable String phone) {
        if (error == null || !PhoneRules.normalize(phone).equals(error.phone)) {
            return null;
        }
        return error.message;
    }

    /** 第 1 步：原手机号合法且没有服务器错误；登录密码非空，或恢复码满 12 位且字符合法 */
    private boolean isIdentityReady() {
        String phone = oldPhone.getValue();
        if (!PhoneRules.isValid(phone) || errorFor(oldPhoneServerError.getValue(), phone) != null) {
            return false;
        }
        if (RecoverMethod.RECOVERY_CODE.equals(currentMethod())) {
            return RecoveryCodeRules.isValid(recoveryCode.getValue());
        }
        String pwd = password.getValue();
        return pwd != null && !pwd.isEmpty();
    }

    private boolean isNewPhoneUsable(@Nullable String phone) {
        return PhoneRules.isValid(phone)
                && !PhoneRules.same(phone, oldPhone.getValue())
                && errorFor(newPhoneServerError.getValue(), phone) == null;
    }

    /** 第 1 步是用恢复码通过的（直接读 verifiedMethod，不依赖 needNewPassword 是否有观察者） */
    private boolean isVerifiedByRecoveryCode() {
        return RecoverMethod.RECOVERY_CODE.equals(verifiedMethod.getValue());
    }

    /** 恢复码方式：新密码符合规则且两次一致；登录密码方式不需要 */
    private boolean isNewPasswordReady() {
        if (!isVerifiedByRecoveryCode()) {
            return true;
        }
        String p = newPassword.getValue();
        return PasswordRules.isValid(p) && p.equals(confirm.getValue());
    }

    private boolean isRebindReady() {
        return isNewPhoneUsable(newPhone.getValue()) && PhoneRules.isSmsCode(code.getValue()) && isNewPasswordReady();
    }

    private void updateCanNext() {
        setIfChanged(canNext, isIdentityReady() && !Boolean.TRUE.equals(verifying.getValue()));
    }

    private void updateCanSendCode() {
        setIfChanged(canSendCode, isNewPhoneUsable(newPhone.getValue())
                && !countdown.isCounting()
                && !Boolean.TRUE.equals(sendingCode.getValue()));
    }

    private void updateCanSubmit() {
        setIfChanged(canSubmit, isRebindReady() && !Boolean.TRUE.equals(submitting.getValue()));
    }

    /** 原手机号下方：格式错误 > 服务器错误（未注册） */
    private void updateOldPhoneError() {
        String phone = oldPhone.getValue();
        String error = PhoneRules.isDefinitelyInvalid(phone)
                ? AccountTexts.PHONE_INVALID
                : errorFor(oldPhoneServerError.getValue(), phone);
        setIfChanged(oldPhoneError, error);
    }

    /** 恢复码下方：字符不合法（错误）> 服务器错误 40105（错误）> 默认说明 */
    private void updateRecoveryCodeHint() {
        String hint;
        boolean error;
        if (RecoveryCodeRules.hasInvalidChars(recoveryCode.getValue())) {
            hint = AccountTexts.RECOVERY_CODE_CHARS;
            error = true;
        } else if (recoveryCodeServerError.getValue() != null) {
            hint = recoveryCodeServerError.getValue();
            error = true;
        } else {
            hint = AccountTexts.RECOVERY_CODE_HINT;
            error = false;
        }
        setIfChanged(recoveryCodeHint, hint);
        setIfChanged(recoveryCodeHintError, error);
    }

    /** 新手机号下方：格式错误 > 与原号相同 > 服务器错误（已注册） */
    private void updateNewPhoneError() {
        String phone = newPhone.getValue();
        String error;
        if (PhoneRules.isDefinitelyInvalid(phone)) {
            error = AccountTexts.PHONE_INVALID;
        } else if (PhoneRules.isValid(phone) && PhoneRules.same(phone, oldPhone.getValue())) {
            error = AccountTexts.SAME_PHONE;
        } else {
            error = errorFor(newPhoneServerError.getValue(), phone);
        }
        setIfChanged(newPhoneError, error);
    }

    /** 验证码下方：验证码错误（错误）> 已发送提示（仍是该号码时）> 默认说明 */
    private void updateCodeHint() {
        String hint;
        boolean error;
        String sent = sentPhone.getValue();
        if (codeError.getValue() != null) {
            hint = codeError.getValue();
            error = true;
        } else if (sent != null && sent.equals(PhoneRules.normalize(newPhone.getValue()))) {
            hint = AccountTexts.sentHintLong(sent);
            error = false;
        } else {
            hint = AccountTexts.REBIND_CODE_HINT;
            error = false;
        }
        setIfChanged(codeHint, hint);
        setIfChanged(codeHintError, error);
    }

    private void updateConfirmHint() {
        String c = confirm.getValue();
        String hint;
        boolean error;
        if (c == null || c.isEmpty()) {
            hint = null;
            error = false;
        } else if (c.equals(newPassword.getValue())) {
            hint = AccountTexts.CONFIRM_MATCHED;
            error = false;
        } else {
            hint = AccountTexts.CONFIRM_MISMATCH;
            error = true;
        }
        setIfChanged(confirmHint, hint);
        setIfChanged(confirmHintError, error);
    }

    private static <T> void setIfChanged(@NonNull MutableLiveData<T> target, @Nullable T value) {
        if (!Objects.equals(value, target.getValue())) {
            target.setValue(value);
        }
    }

    private int currentStep() {
        Integer value = step.getValue();
        return value == null ? STEP_VERIFY : value;
    }

    @VisibleForTesting
    void setClock(@NonNull LongSupplier clock) {
        this.clock = clock;
    }

    /** 服务器针对某个号码返回的错误 */
    private static final class FieldError {
        final String phone;
        final String message;

        FieldError(@NonNull String phone, @NonNull String message) {
            this.phone = phone;
            this.message = message;
        }
    }

    @Override
    protected void onCleared() {
        countdown.stop();
        super.onCleared();
    }

    // ---------- LiveData ----------

    public LiveData<Integer> getStep() {
        return step;
    }

    public MutableLiveData<String> getOldPhone() {
        return oldPhone;
    }

    public LiveData<String> getOldPhoneError() {
        return oldPhoneError;
    }

    public LiveData<String> getMethod() {
        return method;
    }

    /** 当前选中「登录密码」（否则为「恢复码」） */
    public LiveData<Boolean> getPasswordMethod() {
        return passwordMethod;
    }

    public MutableLiveData<String> getPassword() {
        return password;
    }

    public LiveData<Boolean> getPasswordVisible() {
        return passwordVisible;
    }

    public LiveData<String> getPasswordError() {
        return passwordError;
    }

    public MutableLiveData<String> getRecoveryCode() {
        return recoveryCode;
    }

    public LiveData<String> getRecoveryCodeHint() {
        return recoveryCodeHint;
    }

    public LiveData<Boolean> getRecoveryCodeHintError() {
        return recoveryCodeHintError;
    }

    public LiveData<Boolean> getVerifying() {
        return verifying;
    }

    public LiveData<Boolean> getCanNext() {
        return canNext;
    }

    public LiveData<String> getVerifiedMethod() {
        return verifiedMethod;
    }

    /** 第 1 步用的是恢复码：第 2 步显示「设置新密码」「确认新密码」 */
    public LiveData<Boolean> getNeedNewPassword() {
        return needNewPassword;
    }

    public MutableLiveData<String> getNewPhone() {
        return newPhone;
    }

    public LiveData<String> getNewPhoneError() {
        return newPhoneError;
    }

    public MutableLiveData<String> getCode() {
        return code;
    }

    public LiveData<String> getCountdownText() {
        return countdown.getText();
    }

    public LiveData<Boolean> getCanSendCode() {
        return canSendCode;
    }

    public LiveData<String> getCodeHint() {
        return codeHint;
    }

    public LiveData<Boolean> getCodeHintError() {
        return codeHintError;
    }

    public MutableLiveData<String> getNewPassword() {
        return newPassword;
    }

    public MutableLiveData<String> getConfirm() {
        return confirm;
    }

    public LiveData<Boolean> getNewPasswordVisible() {
        return newPasswordVisible;
    }

    public LiveData<Boolean> getConfirmVisible() {
        return confirmVisible;
    }

    public LiveData<Integer> getStrengthLevel() {
        return strengthLevel;
    }

    public LiveData<Boolean> getRuleLength() {
        return ruleLength;
    }

    public LiveData<Boolean> getRuleMix() {
        return ruleMix;
    }

    public LiveData<String> getNewPasswordError() {
        return newPasswordError;
    }

    public LiveData<String> getConfirmHint() {
        return confirmHint;
    }

    public LiveData<Boolean> getConfirmHintError() {
        return confirmHintError;
    }

    public LiveData<Boolean> getSubmitting() {
        return submitting;
    }

    public LiveData<Boolean> getCanSubmit() {
        return canSubmit;
    }

    /** 成功态「今后请使用 139****1111 登录，已为你自动登录」 */
    public LiveData<String> getSuccessDesc() {
        return successDesc;
    }

    public LiveData<Boolean> getRecoveryCodeUsed() {
        return recoveryCodeUsed;
    }

    public LiveData<Void> getFocusCode() {
        return focusCode;
    }

    public LiveData<Void> getNavigateMain() {
        return navigateMain;
    }

    public LiveData<Void> getNavigateLogin() {
        return navigateLogin;
    }

    public LiveData<Void> getNavigateRegister() {
        return navigateRegister;
    }
}
