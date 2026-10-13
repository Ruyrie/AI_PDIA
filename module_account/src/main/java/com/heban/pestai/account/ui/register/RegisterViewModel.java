package com.heban.pestai.account.ui.register;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.account.ui.login.LoginEvent;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.account.util.PasswordRules;
import com.heban.pestai.account.util.PhoneRules;
import com.heban.pestai.account.util.SmsCountdown;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.constant.SmsScene;
import com.heban.pestai.network.ApiException;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A05 注册第 1 步：手机号、验证码、设置密码（实时强度与三条规则）、确认密码、协议。
 * 注册成功即视为已登录（Repository 保存 Token），固定进入 A06 完善资料。
 */
public class RegisterViewModel extends BaseViewModel {

    public static final long CLICK_WINDOW_MS = 500;

    private final AccountRepository repository;

    private final MutableLiveData<String> phone = new MutableLiveData<>("");
    private final MutableLiveData<String> code = new MutableLiveData<>("");
    private final MutableLiveData<String> password = new MutableLiveData<>("");
    private final MutableLiveData<String> confirm = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> agreed = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> agreementHint = new MediatorLiveData<>(false);

    private final LiveData<String> phoneError;
    private final LiveData<Integer> strengthLevel;
    private final LiveData<Boolean> ruleLength;
    private final LiveData<Boolean> ruleLetter;
    private final LiveData<Boolean> ruleDigit;
    private final MediatorLiveData<Boolean> confirmMatched = new MediatorLiveData<>(false);
    private final MediatorLiveData<String> confirmError = new MediatorLiveData<>(null);

    private final SmsCountdown countdown = new SmsCountdown();
    private final MutableLiveData<Boolean> sendingCode = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSendCode = new MediatorLiveData<>(false);
    private final MutableLiveData<String> sentHint = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canNext = new MediatorLiveData<>(false);

    private final SingleLiveEvent<LoginEvent> result = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> agreementShake = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> focusCode = new SingleLiveEvent<>();

    private final PublishSubject<Boolean> sendClicks = PublishSubject.create();
    private final PublishSubject<Boolean> nextClicks = PublishSubject.create();

    @Inject
    public RegisterViewModel(AccountRepository repository) {
        this.repository = repository;
        phoneError = Transformations.map(phone,
                p -> PhoneRules.isDefinitelyInvalid(p) ? AccountTexts.PHONE_INVALID : null);
        strengthLevel = Transformations.map(password, PasswordRules::level);
        ruleLength = Transformations.map(password, PasswordRules::lengthOk);
        ruleLetter = Transformations.map(password, PasswordRules::hasLetter);
        ruleDigit = Transformations.map(password, PasswordRules::hasDigit);

        confirmMatched.addSource(password, p -> updateConfirm());
        confirmMatched.addSource(confirm, c -> updateConfirm());
        confirmError.addSource(confirmMatched, m -> updateConfirmError());

        canSendCode.addSource(phone, p -> updateCanSendCode());
        canSendCode.addSource(countdown.getCounting(), c -> updateCanSendCode());
        canSendCode.addSource(sendingCode, s -> updateCanSendCode());

        canNext.addSource(phone, v -> updateCanNext());
        canNext.addSource(code, v -> updateCanNext());
        canNext.addSource(password, v -> updateCanNext());
        canNext.addSource(confirmMatched, v -> updateCanNext());
        canNext.addSource(loading, v -> updateCanNext());

        agreementHint.addSource(agreed, checked -> {
            if (Boolean.TRUE.equals(checked)) {
                agreementHint.setValue(false);
            }
        });
        add(sendClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSendCode()));
        add(nextClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doRegister()));
    }

    // ---------- 操作 ----------

    public void sendCode() {
        sendClicks.onNext(Boolean.TRUE);
    }

    /** 「下一步」：注册账号 */
    public void next() {
        nextClicks.onNext(Boolean.TRUE);
    }

    private void doSendCode() {
        if (countdown.isCounting() || Boolean.TRUE.equals(sendingCode.getValue())) {
            return;
        }
        final String value = phone.getValue();
        if (!PhoneRules.isValid(value)) {
            showToast(AccountTexts.PHONE_INVALID);
            return;
        }
        sendingCode.setValue(true);
        add(repository.sendCode(value, SmsScene.REGISTER)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> sendingCode.setValue(false))
                .subscribe(() -> {
                    sentHint.setValue(AccountTexts.sentHintLong(value));
                    countdown.start();
                    focusCode.call();
                }, e -> showToast(errorMessage(ApiException.codeOf(e), ApiException.messageOf(e)))));
    }

    private void doRegister() {
        if (Boolean.TRUE.equals(loading.getValue())) {
            return;
        }
        if (!Boolean.TRUE.equals(agreed.getValue())) {
            agreementHint.setValue(true);
            agreementShake.call();
            return;
        }
        String phoneValue = phone.getValue();
        if (!PhoneRules.isValid(phoneValue)) {
            showToast(AccountTexts.PHONE_INVALID);
            return;
        }
        String codeValue = code.getValue();
        if (!PhoneRules.isSmsCode(codeValue)) {
            showToast(AccountTexts.CODE_INVALID);
            return;
        }
        String pwd = password.getValue();
        if (!PasswordRules.isValid(pwd)) {
            showToast(AccountTexts.PASSWORD_RULE);
            return;
        }
        if (!Boolean.TRUE.equals(confirmMatched.getValue())) {
            showToast(AccountTexts.CONFIRM_MISMATCH);
            return;
        }
        loading.setValue(true);
        add(repository.register(phoneValue, codeValue, pwd)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> loading.setValue(false))
                .subscribe(vo -> result.setValue(LoginEvent.GO_PROFILE_SETUP),
                        e -> showToast(errorMessage(ApiException.codeOf(e), ApiException.messageOf(e)))));
    }

    /** 注册相关错误码文案（UI 设计文档 2.5） */
    @NonNull
    static String errorMessage(int errorCode, @Nullable String fallback) {
        switch (errorCode) {
            case ApiException.PHONE_REGISTERED:
                return AccountTexts.PHONE_REGISTERED_REGISTER;
            case ApiException.SMS_CODE_INVALID:
                return AccountTexts.CODE_EXPIRED;
            case ApiException.PARAM_ERROR:
                return AccountTexts.PASSWORD_RULE;
            default:
                return fallback == null ? ApiException.defaultMessage(errorCode) : fallback;
        }
    }

    private void updateConfirm() {
        String p = password.getValue();
        String c = confirm.getValue();
        boolean matched = c != null && !c.isEmpty() && c.equals(p);
        if (!Boolean.valueOf(matched).equals(confirmMatched.getValue())) {
            confirmMatched.setValue(matched);
        }
        updateConfirmError();
    }

    /** 确认密码长度不小于新密码但内容不一致时提示「两次输入的密码不一致」 */
    private void updateConfirmError() {
        confirmError.setValue(mismatchError(password.getValue(), confirm.getValue()));
    }

    @Nullable
    static String mismatchError(@Nullable String password, @Nullable String confirm) {
        if (confirm == null || confirm.isEmpty()) {
            return null;
        }
        int pLen = password == null ? 0 : password.length();
        if (confirm.length() >= pLen && !confirm.equals(password)) {
            return AccountTexts.CONFIRM_MISMATCH;
        }
        return null;
    }

    private void updateCanSendCode() {
        canSendCode.setValue(PhoneRules.isValid(phone.getValue())
                && !countdown.isCounting()
                && !Boolean.TRUE.equals(sendingCode.getValue()));
    }

    private void updateCanNext() {
        canNext.setValue(PhoneRules.isValid(phone.getValue())
                && PhoneRules.isSmsCode(code.getValue())
                && PasswordRules.isValid(password.getValue())
                && Boolean.TRUE.equals(confirmMatched.getValue())
                && !Boolean.TRUE.equals(loading.getValue()));
    }

    @Override
    protected void onCleared() {
        countdown.stop();
        super.onCleared();
    }

    // ---------- LiveData ----------

    public MutableLiveData<String> getPhone() {
        return phone;
    }

    public MutableLiveData<String> getCode() {
        return code;
    }

    public MutableLiveData<String> getPassword() {
        return password;
    }

    public MutableLiveData<String> getConfirm() {
        return confirm;
    }

    public MutableLiveData<Boolean> getAgreed() {
        return agreed;
    }

    public LiveData<Boolean> getAgreementHint() {
        return agreementHint;
    }

    public LiveData<String> getPhoneError() {
        return phoneError;
    }

    public LiveData<Integer> getStrengthLevel() {
        return strengthLevel;
    }

    public LiveData<Boolean> getRuleLength() {
        return ruleLength;
    }

    public LiveData<Boolean> getRuleLetter() {
        return ruleLetter;
    }

    public LiveData<Boolean> getRuleDigit() {
        return ruleDigit;
    }

    public LiveData<Boolean> getConfirmMatched() {
        return confirmMatched;
    }

    public LiveData<String> getConfirmError() {
        return confirmError;
    }

    public LiveData<String> getCountdownText() {
        return countdown.getText();
    }

    public LiveData<Boolean> getCanSendCode() {
        return canSendCode;
    }

    public LiveData<String> getSentHint() {
        return sentHint;
    }

    public LiveData<Boolean> getLoading() {
        return loading;
    }

    public LiveData<Boolean> getCanNext() {
        return canNext;
    }

    public LiveData<LoginEvent> getResult() {
        return result;
    }

    public LiveData<Void> getAgreementShake() {
        return agreementShake;
    }

    public LiveData<Void> getFocusCode() {
        return focusCode;
    }
}
