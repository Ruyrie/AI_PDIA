package com.heban.pestai.account.ui.deactivate;

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
import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.network.ApiException;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A11 注销账号：验证当前手机号（DEACTIVATE 验证码由服务器发往当前账号手机号）+ 勾选已了解后果 + 二次确认后立即注销。
 * <p>「注销账号」按钮只发出二次确认事件，对话框点「确定注销」后才调用 {@link #confirmDeactivate()} 提交。
 * 成功后通过 IUserService.logout() 清除本地登录态（与 ServiceHub 中注册的是同一个单例），再由页面清栈打开登录页。</p>
 */
public class DeactivateViewModel extends BaseViewModel {

    public static final long SEND_WINDOW_MS = 1000;
    public static final long CLICK_WINDOW_MS = 500;

    private final AccountRepository repository;
    private final IUserService userService;

    @Nullable
    private final String currentPhone;
    private final String currentMasked;

    private final MutableLiveData<String> code = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> agreed = new MutableLiveData<>(false);
    private final SmsCountdown countdown = new SmsCountdown();
    private final MutableLiveData<Boolean> sendingCode = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSendCode = new MediatorLiveData<>(false);
    private final MutableLiveData<String> sentHint = new MutableLiveData<>(null);
    /** 提交返回 40103 后的验证码错误提示，修改验证码即清除 */
    private final MutableLiveData<String> codeError = new MutableLiveData<>(null);
    private final MediatorLiveData<String> codeHint = new MediatorLiveData<>(null);
    private final MediatorLiveData<Boolean> codeHintError = new MediatorLiveData<>(false);
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSubmit = new MediatorLiveData<>(false);

    private final SingleLiveEvent<Void> showConfirm = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> deactivated = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> focusCode = new SingleLiveEvent<>();
    private final PublishSubject<Boolean> sendClicks = PublishSubject.create();
    private final PublishSubject<Boolean> submitClicks = PublishSubject.create();
    private final PublishSubject<Boolean> confirmClicks = PublishSubject.create();

    @Inject
    public DeactivateViewModel(AccountRepository repository, IUserService userService) {
        this.repository = repository;
        this.userService = userService;
        currentPhone = repository.currentPhone();
        currentMasked = PhoneRules.mask(currentPhone);

        canSendCode.addSource(countdown.getCounting(), v -> updateCanSendCode());
        canSendCode.addSource(sendingCode, v -> updateCanSendCode());

        codeHint.addSource(sentHint, v -> updateCodeHint());
        codeHint.addSource(codeError, v -> updateCodeHint());

        canSubmit.addSource(agreed, v -> updateCanSubmit());
        canSubmit.addSource(code, v -> {
            if (codeError.getValue() != null) {
                codeError.setValue(null);
            }
            updateCanSubmit();
        });
        canSubmit.addSource(loading, v -> updateCanSubmit());

        add(sendClicks.throttleFirst(SEND_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSendCode()));
        add(submitClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSubmit()));
        add(confirmClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doDeactivate()));
    }

    // ---------- 操作 ----------

    /** 「获取验证码」：发往当前手机号 */
    public void sendCode() {
        sendClicks.onNext(Boolean.TRUE);
    }

    /** 「注销账号」按钮：条件满足时弹出二次确认 */
    public void submit() {
        submitClicks.onNext(Boolean.TRUE);
    }

    /** 二次确认对话框「确定注销」 */
    public void confirmDeactivate() {
        confirmClicks.onNext(Boolean.TRUE);
    }

    private void doSendCode() {
        if (countdown.isCounting() || Boolean.TRUE.equals(sendingCode.getValue())) {
            return;
        }
        sendingCode.setValue(true);
        add(repository.sendDeactivateCode()
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> sendingCode.setValue(false))
                .subscribe(() -> {
                    sentHint.setValue(currentPhone == null
                            ? AccountTexts.SENT_TO_CURRENT
                            : AccountTexts.sentHintLong(currentPhone));
                    countdown.start();
                    focusCode.call();
                }, e -> showToast(ApiException.messageOf(e))));
    }

    private void doSubmit() {
        if (isReady() && !Boolean.TRUE.equals(loading.getValue())) {
            showConfirm.call();
        }
    }

    private void doDeactivate() {
        if (!isReady() || Boolean.TRUE.equals(loading.getValue())) {
            return;
        }
        loading.setValue(true);
        add(repository.deactivate(code.getValue())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> loading.setValue(false))
                .subscribe(() -> {
                    countdown.stop();
                    // 账号已删除：清除 token、user_json、is_guest，并通知其他组件
                    userService.logout();
                    deactivated.call();
                }, e -> {
                    if (ApiException.codeOf(e) == ApiException.SMS_CODE_INVALID) {
                        code.setValue("");
                        codeError.setValue(AccountTexts.CODE_EXPIRED);
                    } else {
                        showToast(ApiException.messageOf(e));
                    }
                }));
    }

    // ---------- 校验 ----------

    /** 已勾选「我已了解注销后果」且验证码为 6 位数字 */
    private boolean isReady() {
        return Boolean.TRUE.equals(agreed.getValue()) && PhoneRules.isSmsCode(code.getValue());
    }

    private void updateCanSendCode() {
        canSendCode.setValue(!countdown.isCounting() && !Boolean.TRUE.equals(sendingCode.getValue()));
    }

    private void updateCanSubmit() {
        canSubmit.setValue(isReady() && !Boolean.TRUE.equals(loading.getValue()));
    }

    /** 验证码下方：验证码错误（错误样式）> 已发送提示 */
    private void updateCodeHint() {
        String hint;
        boolean error;
        if (codeError.getValue() != null) {
            hint = codeError.getValue();
            error = true;
        } else {
            hint = sentHint.getValue();
            error = false;
        }
        if (!Objects.equals(hint, codeHint.getValue())) {
            codeHint.setValue(hint);
        }
        if (!Boolean.valueOf(error).equals(codeHintError.getValue())) {
            codeHintError.setValue(error);
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

    public MutableLiveData<String> getCode() {
        return code;
    }

    public MutableLiveData<Boolean> getAgreed() {
        return agreed;
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

    public LiveData<Void> getShowConfirm() {
        return showConfirm;
    }

    public LiveData<Void> getDeactivated() {
        return deactivated;
    }

    public LiveData<Void> getFocusCode() {
        return focusCode;
    }
}
