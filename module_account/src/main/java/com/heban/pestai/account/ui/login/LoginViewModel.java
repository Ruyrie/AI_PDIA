package com.heban.pestai.account.ui.login;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.account.util.PhoneRules;
import com.heban.pestai.account.util.SmsCountdown;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.constant.SmsScene;
import com.heban.pestai.common.vo.LoginVO;
import com.heban.pestai.network.ApiException;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A03 密码登录 / A04 验证码登录共用：mode 区分两种方式，手机号与协议勾选在切换 Tab 时保留。
 */
public class LoginViewModel extends BaseViewModel {

    public static final long CLICK_WINDOW_MS = 500;

    public enum Mode {
        PASSWORD, SMS
    }

    private final AccountRepository repository;

    private final MutableLiveData<Mode> mode = new MutableLiveData<>(Mode.PASSWORD);
    private final LiveData<Boolean> smsMode = Transformations.map(mode, m -> m == Mode.SMS);
    private final MutableLiveData<String> phone = new MutableLiveData<>("");
    private final MutableLiveData<String> password = new MutableLiveData<>("");
    private final MutableLiveData<String> code = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> agreed = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> agreementHint = new MediatorLiveData<>(false);
    private final LiveData<String> phoneError;
    private final SmsCountdown countdown = new SmsCountdown();
    private final MutableLiveData<Boolean> sendingCode = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSendCode = new MediatorLiveData<>(false);
    private final MutableLiveData<String> sentHint = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);

    private final SingleLiveEvent<LoginEvent> result = new SingleLiveEvent<>();
    /** 未勾选协议时复选框抖动 */
    private final SingleLiveEvent<Void> agreementShake = new SingleLiveEvent<>();
    /** 验证码发送成功后让验证码输入框获得焦点 */
    private final SingleLiveEvent<Void> focusCode = new SingleLiveEvent<>();
    /** 验证码错误时全选验证码，方便重新输入 */
    private final SingleLiveEvent<Void> selectCode = new SingleLiveEvent<>();

    private final PublishSubject<Boolean> sendClicks = PublishSubject.create();
    private final PublishSubject<Boolean> loginClicks = PublishSubject.create();

    @Inject
    public LoginViewModel(AccountRepository repository) {
        this.repository = repository;
        phoneError = Transformations.map(phone,
                p -> PhoneRules.isDefinitelyInvalid(p) ? AccountTexts.PHONE_INVALID : null);
        canSendCode.addSource(phone, p -> updateCanSendCode());
        canSendCode.addSource(countdown.getCounting(), c -> updateCanSendCode());
        canSendCode.addSource(sendingCode, s -> updateCanSendCode());
        agreementHint.addSource(agreed, checked -> {
            if (Boolean.TRUE.equals(checked)) {
                agreementHint.setValue(false);
            }
        });
        add(sendClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSendCode()));
        add(loginClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doLogin()));
    }

    // ---------- 操作 ----------

    /** 切换登录方式（Tab），手机号与协议保留 */
    public void switchMode(@NonNull Mode target) {
        if (mode.getValue() != target) {
            mode.setValue(target);
        }
    }

    /** 获取验证码（500ms 防连点） */
    public void sendCode() {
        sendClicks.onNext(Boolean.TRUE);
    }

    /** 登录 / 登录·注册（500ms 防连点 + loading 双重防重复提交） */
    public void login() {
        loginClicks.onNext(Boolean.TRUE);
    }

    /** 游客浏览：不请求服务器，本地 is_guest 置为 true 后进入首页 */
    public void enterGuest() {
        repository.enterGuest();
        result.setValue(LoginEvent.GO_MAIN);
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
        add(repository.sendCode(value, SmsScene.LOGIN)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> sendingCode.setValue(false))
                .subscribe(() -> {
                    sentHint.setValue(AccountTexts.sentHintLong(value));
                    countdown.start();
                    focusCode.call();
                }, e -> showToast(ApiException.messageOf(e))));
    }

    private void doLogin() {
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
        final Mode current = currentMode();
        Single<LoginVO> request;
        if (current == Mode.PASSWORD) {
            String pwd = password.getValue();
            if (pwd == null || pwd.isEmpty()) {
                showToast(AccountTexts.PASSWORD_EMPTY);
                return;
            }
            request = repository.loginByPassword(phoneValue, pwd);
        } else {
            String smsCode = code.getValue();
            if (!PhoneRules.isSmsCode(smsCode)) {
                showToast(AccountTexts.CODE_INVALID);
                return;
            }
            request = repository.loginBySms(phoneValue, smsCode);
        }
        loading.setValue(true);
        add(request.observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> loading.setValue(false))
                .subscribe(vo -> result.setValue(vo.isNew() ? LoginEvent.GO_PROFILE_SETUP : LoginEvent.GO_MAIN),
                        e -> onLoginError(e, current)));
    }

    private void onLoginError(@NonNull Throwable e, @NonNull Mode current) {
        int errorCode = ApiException.codeOf(e);
        showToast(errorMessage(errorCode, ApiException.messageOf(e), current));
        if (errorCode == ApiException.SMS_CODE_INVALID) {
            selectCode.call();
        }
    }

    /** 按错误码选择文案（UI 设计文档 2.5） */
    @NonNull
    static String errorMessage(int errorCode, @Nullable String fallback, @NonNull Mode mode) {
        switch (errorCode) {
            case ApiException.NOT_REGISTERED:
                return mode == Mode.PASSWORD ? AccountTexts.NOT_REGISTERED_LOGIN : AccountTexts.NOT_REGISTERED;
            case ApiException.WRONG_PASSWORD:
                return AccountTexts.WRONG_PASSWORD;
            case ApiException.SMS_CODE_INVALID:
                return AccountTexts.CODE_EXPIRED;
            case ApiException.ACCOUNT_DISABLED:
                return AccountTexts.ACCOUNT_DISABLED;
            default:
                return fallback == null ? ApiException.defaultMessage(errorCode) : fallback;
        }
    }

    private void updateCanSendCode() {
        canSendCode.setValue(PhoneRules.isValid(phone.getValue())
                && !countdown.isCounting()
                && !Boolean.TRUE.equals(sendingCode.getValue()));
    }

    @NonNull
    private Mode currentMode() {
        Mode value = mode.getValue();
        return value == null ? Mode.PASSWORD : value;
    }

    @Override
    protected void onCleared() {
        countdown.stop();
        super.onCleared();
    }

    // ---------- LiveData ----------

    public LiveData<Mode> getMode() {
        return mode;
    }

    /** 是否为验证码登录（A04），布局据此切换标题、输入框与底部说明 */
    public LiveData<Boolean> getSmsMode() {
        return smsMode;
    }

    public MutableLiveData<String> getPhone() {
        return phone;
    }

    public MutableLiveData<String> getPassword() {
        return password;
    }

    public MutableLiveData<String> getCode() {
        return code;
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

    public LiveData<String> getCountdownText() {
        return countdown.getText();
    }

    public LiveData<Boolean> getCounting() {
        return countdown.getCounting();
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

    public LiveData<LoginEvent> getResult() {
        return result;
    }

    public LiveData<Void> getAgreementShake() {
        return agreementShake;
    }

    public LiveData<Void> getFocusCode() {
        return focusCode;
    }

    public LiveData<Void> getSelectCode() {
        return selectCode;
    }
}
