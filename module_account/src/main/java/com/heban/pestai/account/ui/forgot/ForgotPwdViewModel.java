package com.heban.pestai.account.ui.forgot;

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
import com.heban.pestai.account.util.PhoneRules;
import com.heban.pestai.account.util.SmsCountdown;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.constant.SmsScene;
import com.heban.pestai.common.vo.ResetTicketVO;
import com.heban.pestai.network.ApiException;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A07 找回密码 · 验证手机：发送 RESET 场景验证码 → auth/password/verify 换取一次性 reset_ticket → 进入 A08。
 * 从设置页「修改密码」进入时（EXTRA_FROM_SETTINGS）手机号预填且不可修改。
 */
public class ForgotPwdViewModel extends BaseViewModel {

    /** 发送验证码防连点窗口（模块03 技术文档：1 秒） */
    public static final long SEND_WINDOW_MS = 1000;
    public static final long CLICK_WINDOW_MS = 500;
    /** 本地截止时刻预留的余量（抵消网络传输与操作耗时） */
    public static final long DEADLINE_MARGIN_MS = 30_000;

    private final AccountRepository repository;

    private final MutableLiveData<String> phone = new MutableLiveData<>("");
    private final MutableLiveData<String> code = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> phoneEditable = new MutableLiveData<>(true);
    private final LiveData<String> phoneError;
    private final SmsCountdown countdown = new SmsCountdown();
    private final MutableLiveData<Boolean> sendingCode = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSendCode = new MediatorLiveData<>(false);
    private final MutableLiveData<String> sentHint = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canNext = new MediatorLiveData<>(false);
    private final SingleLiveEvent<ResetTicketVO> verified = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> openRecover = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> focusCode = new SingleLiveEvent<>();

    private final PublishSubject<Boolean> sendClicks = PublishSubject.create();
    private final PublishSubject<Boolean> nextClicks = PublishSubject.create();

    private LongSupplier clock = SystemClock::elapsedRealtime;
    private boolean fromSettings;
    private long ticketDeadline;
    private boolean initialized;

    @Inject
    public ForgotPwdViewModel(AccountRepository repository) {
        this.repository = repository;
        phoneError = Transformations.map(phone,
                p -> PhoneRules.isDefinitelyInvalid(p) ? AccountTexts.PHONE_INVALID : null);
        canSendCode.addSource(phone, v -> updateCanSendCode());
        canSendCode.addSource(countdown.getCounting(), v -> updateCanSendCode());
        canSendCode.addSource(sendingCode, v -> updateCanSendCode());
        canNext.addSource(phone, v -> updateCanNext());
        canNext.addSource(code, v -> updateCanNext());
        canNext.addSource(loading, v -> updateCanNext());
        add(sendClicks.throttleFirst(SEND_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSendCode()));
        add(nextClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doNext()));
    }

    /**
     * 接收 Intent 中的 EXTRA_PHONE、EXTRA_FROM_SETTINGS；只在 ViewModel 首次创建时执行。
     * 从设置页进入且号码合法时预填并锁定；号码为空或不合法时退化为普通找回流程。
     */
    public void init(@Nullable String presetPhone, boolean fromSettings) {
        if (initialized) {
            return;
        }
        initialized = true;
        boolean valid = PhoneRules.isValid(presetPhone);
        // EXTRA_FROM_SETTINGS 原样转发给 A08（决定成功后是否清除本地登录态）；只有号码合法时才锁定输入框
        this.fromSettings = fromSettings;
        if (valid) {
            phone.setValue(PhoneRules.format(presetPhone));
        }
        phoneEditable.setValue(!(fromSettings && valid));
    }

    // ---------- 操作 ----------

    public void sendCode() {
        sendClicks.onNext(Boolean.TRUE);
    }

    /** 「下一步」：校验验证码，换取 reset_ticket */
    public void next() {
        nextClicks.onNext(Boolean.TRUE);
    }

    /** 「找回账号」提示条（手机号已停用、收不到验证码）→ A09 */
    public void openRecover() {
        openRecover.call();
    }

    /** A08 带回「凭证过期」：清空验证码并提示重新获取 */
    public void onTicketExpired() {
        code.setValue("");
        showToast(AccountTexts.TICKET_EXPIRED);
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
        add(repository.sendCode(value, SmsScene.RESET)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> sendingCode.setValue(false))
                .subscribe(() -> {
                    sentHint.setValue(AccountTexts.sentHintShort(value));
                    countdown.start();
                    focusCode.call();
                }, e -> showToast(errorMessage(ApiException.codeOf(e), ApiException.messageOf(e)))));
    }

    private void doNext() {
        if (Boolean.TRUE.equals(loading.getValue())) {
            return;
        }
        String phoneValue = phone.getValue();
        String codeValue = code.getValue();
        if (!PhoneRules.isValid(phoneValue)) {
            showToast(AccountTexts.PHONE_INVALID);
            return;
        }
        if (!PhoneRules.isSmsCode(codeValue)) {
            showToast(AccountTexts.CODE_INVALID);
            return;
        }
        loading.setValue(true);
        add(repository.verifyResetCode(phoneValue, codeValue)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> loading.setValue(false))
                .subscribe(ticket -> {
                    ticketDeadline = clock.getAsLong() + ticket.getExpireIn() * 1000L - DEADLINE_MARGIN_MS;
                    verified.setValue(ticket);
                }, e -> {
                    int errorCode = ApiException.codeOf(e);
                    if (errorCode == ApiException.SMS_CODE_INVALID) {
                        code.setValue("");
                    }
                    showToast(errorMessage(errorCode, ApiException.messageOf(e)));
                }));
    }

    @NonNull
    static String errorMessage(int errorCode, @Nullable String fallback) {
        switch (errorCode) {
            case ApiException.NOT_REGISTERED:
                return AccountTexts.NOT_REGISTERED;
            case ApiException.SMS_CODE_INVALID:
                return AccountTexts.CODE_EXPIRED;
            default:
                return fallback == null ? ApiException.defaultMessage(errorCode) : fallback;
        }
    }

    private void updateCanSendCode() {
        canSendCode.setValue(PhoneRules.isValid(phone.getValue())
                && !countdown.isCounting()
                && !Boolean.TRUE.equals(sendingCode.getValue()));
    }

    private void updateCanNext() {
        canNext.setValue(PhoneRules.isValid(phone.getValue())
                && PhoneRules.isSmsCode(code.getValue())
                && !Boolean.TRUE.equals(loading.getValue()));
    }

    @VisibleForTesting
    void setClock(@NonNull LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    protected void onCleared() {
        countdown.stop();
        super.onCleared();
    }

    // ---------- 状态 ----------

    /** 去空格后的手机号（跳转 A08 时携带） */
    @NonNull
    public String getNormalizedPhone() {
        return PhoneRules.normalize(phone.getValue());
    }

    /** 收到 ResetTicketVO 时计算的凭证截止时刻（SystemClock.elapsedRealtime() 基准） */
    public long getTicketDeadline() {
        return ticketDeadline;
    }

    public boolean isFromSettings() {
        return fromSettings;
    }

    public MutableLiveData<String> getPhone() {
        return phone;
    }

    public MutableLiveData<String> getCode() {
        return code;
    }

    public LiveData<Boolean> getPhoneEditable() {
        return phoneEditable;
    }

    public LiveData<String> getPhoneError() {
        return phoneError;
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

    public LiveData<ResetTicketVO> getVerified() {
        return verified;
    }

    public LiveData<Void> getOpenRecover() {
        return openRecover;
    }

    public LiveData<Void> getFocusCode() {
        return focusCode;
    }
}
