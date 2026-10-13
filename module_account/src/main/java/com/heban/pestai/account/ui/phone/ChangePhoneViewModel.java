package com.heban.pestai.account.ui.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.account.util.PhoneRules;
import com.heban.pestai.account.util.SmsCountdown;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.network.ApiException;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A10 更换手机号（已登录）：只验证新手机号收到的 CHANGE_PHONE 验证码即可换绑，不需要验证原手机号。
 * <ul>
 *     <li>新号与当前号相同：本地校验，新手机号输入框下方提示，不能获取验证码、不能提交；</li>
 *     <li>40901（发送验证码或提交时）：新手机号输入框下方提示「该手机号已注册…」，直到号码被修改；</li>
 *     <li>40103：清空验证码，验证码输入框下方提示「验证码错误或已过期」，重新输入即清除；</li>
 *     <li>成功：仓库已用 UserSession.saveLogin() 保存新令牌（本机保持登录），页面切换为成功态。</li>
 * </ul>
 */
public class ChangePhoneViewModel extends BaseViewModel {

    /** 发送验证码防连点窗口（与 A07 / A09 一致：1 秒） */
    public static final long SEND_WINDOW_MS = 1000;
    public static final long CLICK_WINDOW_MS = 500;

    private final AccountRepository repository;

    /** 当前账号手机号（去空格），没有资料时为 null */
    @Nullable
    private final String currentPhone;
    private final String currentMasked;

    private final MutableLiveData<String> newPhone = new MutableLiveData<>("");
    private final MutableLiveData<String> code = new MutableLiveData<>("");
    /**
     * 服务器对某个号码给出的错误（40901 已注册；40001「与当前手机号相同」）。
     * 只在输入框中仍是该号码时显示，修改号码后自动消失。
     */
    private final MutableLiveData<PhoneError> serverPhoneError = new MutableLiveData<>(null);
    private final MediatorLiveData<String> newPhoneError = new MediatorLiveData<>(null);
    private final SmsCountdown countdown = new SmsCountdown();
    private final MutableLiveData<Boolean> sendingCode = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSendCode = new MediatorLiveData<>(false);
    /** 最近一次成功发送验证码的号码（去空格）与提示文字 */
    private final MutableLiveData<String> sentPhone = new MutableLiveData<>(null);
    /** 提交返回 40103 后的验证码错误提示，修改验证码即清除 */
    private final MutableLiveData<String> codeError = new MutableLiveData<>(null);
    private final MediatorLiveData<String> codeHint = new MediatorLiveData<>(null);
    private final MediatorLiveData<Boolean> codeHintError = new MediatorLiveData<>(false);
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSubmit = new MediatorLiveData<>(false);
    private final MutableLiveData<Boolean> done = new MutableLiveData<>(false);
    private final MutableLiveData<String> successDesc = new MutableLiveData<>("");

    private final SingleLiveEvent<Void> focusCode = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> finish = new SingleLiveEvent<>();
    private final PublishSubject<Boolean> sendClicks = PublishSubject.create();
    private final PublishSubject<Boolean> submitClicks = PublishSubject.create();

    @Inject
    public ChangePhoneViewModel(AccountRepository repository) {
        this.repository = repository;
        currentPhone = repository.currentPhone();
        currentMasked = PhoneRules.mask(currentPhone);

        newPhoneError.addSource(newPhone, v -> updatePhoneError());
        newPhoneError.addSource(serverPhoneError, v -> updatePhoneError());

        canSendCode.addSource(newPhone, v -> updateCanSendCode());
        canSendCode.addSource(serverPhoneError, v -> updateCanSendCode());
        canSendCode.addSource(countdown.getCounting(), v -> updateCanSendCode());
        canSendCode.addSource(sendingCode, v -> updateCanSendCode());

        codeHint.addSource(newPhone, v -> updateCodeHint());
        codeHint.addSource(sentPhone, v -> updateCodeHint());
        codeHint.addSource(codeError, v -> updateCodeHint());

        canSubmit.addSource(newPhone, v -> updateCanSubmit());
        canSubmit.addSource(serverPhoneError, v -> updateCanSubmit());
        canSubmit.addSource(code, v -> {
            if (codeError.getValue() != null) {
                codeError.setValue(null);
            }
            updateCanSubmit();
        });
        canSubmit.addSource(loading, v -> updateCanSubmit());

        add(sendClicks.throttleFirst(SEND_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSendCode()));
        add(submitClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSubmit()));
    }

    // ---------- 操作 ----------

    /** 「获取验证码」：发往新手机号 */
    public void sendCode() {
        sendClicks.onNext(Boolean.TRUE);
    }

    /** 「确认更换」 */
    public void submit() {
        submitClicks.onNext(Boolean.TRUE);
    }

    /** 成功态「完成」：返回设置页 */
    public void finishPage() {
        finish.call();
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
        if (isSameAsCurrent(target) || serverErrorFor(target) != null) {
            return;
        }
        sendingCode.setValue(true);
        add(repository.sendChangePhoneCode(target)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> sendingCode.setValue(false))
                .subscribe(() -> {
                    sentPhone.setValue(target);
                    countdown.start();
                    focusCode.call();
                }, e -> onPhoneError(target, e)));
    }

    private void doSubmit() {
        if (Boolean.TRUE.equals(loading.getValue()) || Boolean.TRUE.equals(done.getValue()) || !isReady()) {
            return;
        }
        final String target = PhoneRules.normalize(newPhone.getValue());
        loading.setValue(true);
        add(repository.changePhone(target, code.getValue())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> loading.setValue(false))
                .subscribe(login -> {
                    countdown.stop();
                    successDesc.setValue(AccountTexts.changePhoneSuccess(target));
                    done.setValue(true);
                }, e -> {
                    if (ApiException.codeOf(e) == ApiException.SMS_CODE_INVALID) {
                        code.setValue("");
                        codeError.setValue(AccountTexts.CODE_EXPIRED);
                    } else {
                        onPhoneError(target, e);
                    }
                }));
    }

    /** 发送验证码 / 提交失败：与号码有关的错误显示在新手机号输入框下方，其余以 Snackbar 提示 */
    private void onPhoneError(@NonNull String target, @NonNull Throwable e) {
        int errorCode = ApiException.codeOf(e);
        String message = ApiException.messageOf(e);
        if (errorCode == ApiException.PHONE_REGISTERED) {
            serverPhoneError.setValue(new PhoneError(target, AccountTexts.PHONE_REGISTERED_CHANGE));
        } else if (errorCode == ApiException.PARAM_ERROR && AccountTexts.SAME_CURRENT_PHONE.equals(message)) {
            // 本地缓存的号码过期（如已在其他设备换绑）时由服务器判断
            serverPhoneError.setValue(new PhoneError(target, AccountTexts.SAME_CURRENT_PHONE));
        } else {
            showToast(message);
        }
    }

    // ---------- 校验 ----------

    private boolean isSameAsCurrent(@Nullable String phone) {
        return currentPhone != null && PhoneRules.same(phone, currentPhone);
    }

    /** 服务器针对该号码的错误；号码已修改时为 null */
    @Nullable
    private String serverErrorFor(@Nullable String phone) {
        PhoneError error = serverPhoneError.getValue();
        if (error == null || !PhoneRules.normalize(phone).equals(error.phone)) {
            return null;
        }
        return error.message;
    }

    /** 新手机号合法、与当前号不同且没有服务器错误 */
    private boolean isPhoneUsable(@Nullable String phone) {
        return PhoneRules.isValid(phone) && !isSameAsCurrent(phone) && serverErrorFor(phone) == null;
    }

    private boolean isReady() {
        return isPhoneUsable(newPhone.getValue()) && PhoneRules.isSmsCode(code.getValue());
    }

    /** 新手机号下方：格式错误 > 与当前号相同 > 服务器错误 */
    private void updatePhoneError() {
        String phone = newPhone.getValue();
        String error;
        if (PhoneRules.isDefinitelyInvalid(phone)) {
            error = AccountTexts.PHONE_INVALID;
        } else if (PhoneRules.isValid(phone) && isSameAsCurrent(phone)) {
            error = AccountTexts.SAME_CURRENT_PHONE;
        } else {
            error = serverErrorFor(phone);
        }
        if (!Objects.equals(error, newPhoneError.getValue())) {
            newPhoneError.setValue(error);
        }
    }

    private void updateCanSendCode() {
        canSendCode.setValue(isPhoneUsable(newPhone.getValue())
                && !countdown.isCounting()
                && !Boolean.TRUE.equals(sendingCode.getValue()));
    }

    private void updateCanSubmit() {
        canSubmit.setValue(isReady() && !Boolean.TRUE.equals(loading.getValue()));
    }

    /** 验证码下方：验证码错误（错误样式）> 已发送提示（只在输入框仍是该号码时显示） */
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
            hint = null;
            error = false;
        }
        if (!Objects.equals(hint, codeHint.getValue())) {
            codeHint.setValue(hint);
        }
        if (!Boolean.valueOf(error).equals(codeHintError.getValue())) {
            codeHintError.setValue(error);
        }
    }

    /** 服务器针对某个号码返回的错误 */
    private static final class PhoneError {
        final String phone;
        final String message;

        PhoneError(@NonNull String phone, @NonNull String message) {
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

    /** 「138****5678」；没有资料时为空串 */
    public String getCurrentMasked() {
        return currentMasked;
    }

    public MutableLiveData<String> getNewPhone() {
        return newPhone;
    }

    public MutableLiveData<String> getCode() {
        return code;
    }

    public LiveData<String> getNewPhoneError() {
        return newPhoneError;
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

    public LiveData<Boolean> getLoading() {
        return loading;
    }

    public LiveData<Boolean> getCanSubmit() {
        return canSubmit;
    }

    public LiveData<Boolean> getDone() {
        return done;
    }

    /** 成功态说明「今后请使用 139****1111 登录」 */
    public LiveData<String> getSuccessDesc() {
        return successDesc;
    }

    public LiveData<Void> getFocusCode() {
        return focusCode;
    }

    public LiveData<Void> getFinish() {
        return finish;
    }
}
