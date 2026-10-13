package com.heban.pestai.account.ui.reset;

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
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.network.ApiException;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A08 设置新密码：新密码与确认、强度与规则卡、凭重置凭证提交 auth/password/reset。
 * <p>规则卡第三条「与最近使用过的密码不同」只能由后端判断，用 ruleRecent 三态表示：
 * {@link #RECENT_PENDING} 待提交校验 / {@link #RECENT_PASSED} 通过 / {@link #RECENT_FAILED} 未通过。
 * 后端返回的两种 40001 以固定文案区分：「新密码不能与最近使用过的密码相同」→ 第三条标红，凭证仍可用；
 * 其他 40001 视为凭证无效或过期 → 返回 A07 重新获取验证码。</p>
 */
public class ResetPwdViewModel extends BaseViewModel {

    public static final long CLICK_WINDOW_MS = 500;
    public static final int RECENT_PENDING = 0;
    public static final int RECENT_PASSED = 1;
    public static final int RECENT_FAILED = 2;

    private final AccountRepository repository;

    private final MutableLiveData<String> password = new MutableLiveData<>("");
    private final MutableLiveData<String> confirm = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> passwordVisible = new MutableLiveData<>(false);
    private final LiveData<Integer> strengthLevel;
    private final LiveData<Boolean> ruleLength;
    private final LiveData<Boolean> ruleMix;
    private final LiveData<String> passwordError;
    private final MediatorLiveData<Integer> ruleRecent = new MediatorLiveData<>(RECENT_PENDING);
    private final MediatorLiveData<Boolean> confirmMatched = new MediatorLiveData<>(false);
    /** 确认密码下方：一致时「两次输入一致」（primary），不一致时错误提示 */
    private final MediatorLiveData<String> confirmHint = new MediatorLiveData<>(null);
    private final MediatorLiveData<Boolean> confirmHintError = new MediatorLiveData<>(false);
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSubmit = new MediatorLiveData<>(false);
    private final MutableLiveData<Boolean> done = new MutableLiveData<>(false);
    private final SingleLiveEvent<String> expired = new SingleLiveEvent<>();
    private final SingleLiveEvent<Boolean> navigateLogin = new SingleLiveEvent<>();
    private final PublishSubject<Boolean> submitClicks = PublishSubject.create();

    private LongSupplier clock = SystemClock::elapsedRealtime;
    private String phone;
    private String resetTicket;
    private long ticketDeadline;
    private boolean fromSettings;
    private boolean initialized;

    @Inject
    public ResetPwdViewModel(AccountRepository repository) {
        this.repository = repository;
        strengthLevel = Transformations.map(password, PasswordRules::level);
        ruleLength = Transformations.map(password, PasswordRules::lengthOk);
        ruleMix = Transformations.map(password, PasswordRules::mixOk);
        passwordError = Transformations.map(password, ResetPwdViewModel::passwordErrorOf);
        ruleRecent.addSource(password, p -> {
            // 修改新密码后第三条恢复为待校验
            if (ruleRecent.getValue() == null || ruleRecent.getValue() != RECENT_PENDING) {
                ruleRecent.setValue(RECENT_PENDING);
            }
        });
        confirmMatched.addSource(password, v -> updateConfirm());
        confirmMatched.addSource(confirm, v -> updateConfirm());
        confirmHint.addSource(confirmMatched, v -> updateConfirmHint());
        canSubmit.addSource(password, v -> updateCanSubmit());
        canSubmit.addSource(confirmMatched, v -> updateCanSubmit());
        canSubmit.addSource(ruleRecent, v -> updateCanSubmit());
        canSubmit.addSource(loading, v -> updateCanSubmit());
        add(submitClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSubmit()));
    }

    /** 保存 Intent 参数；只在首次创建时执行 */
    public void init(@Nullable String phone, @Nullable String resetTicket, long ticketDeadline, boolean fromSettings) {
        if (initialized) {
            return;
        }
        initialized = true;
        this.phone = phone;
        this.resetTicket = resetTicket;
        this.ticketDeadline = ticketDeadline;
        this.fromSettings = fromSettings;
    }

    // ---------- 操作 ----------

    /** 新密码与确认密码一起切换明文 / 密文 */
    public void togglePasswordVisible() {
        passwordVisible.setValue(!Boolean.TRUE.equals(passwordVisible.getValue()));
    }

    /** 「完成」 */
    public void submit() {
        submitClicks.onNext(Boolean.TRUE);
    }

    /** 成功态「去登录」与返回键共用 */
    public void goLogin() {
        navigateLogin.setValue(fromSettings);
    }

    private void doSubmit() {
        if (Boolean.TRUE.equals(loading.getValue()) || Boolean.TRUE.equals(done.getValue())) {
            return;
        }
        if (resetTicket == null || resetTicket.isEmpty() || clock.getAsLong() > ticketDeadline) {
            expired.setValue(AccountTexts.TICKET_EXPIRED);
            return;
        }
        final String pwd = password.getValue();
        if (!PasswordRules.isValid(pwd)) {
            showToast(AccountTexts.PASSWORD_RULE);
            return;
        }
        if (!Boolean.TRUE.equals(confirmMatched.getValue())) {
            showToast(AccountTexts.CONFIRM_MISMATCH);
            return;
        }
        loading.setValue(true);
        add(repository.resetPassword(phone, resetTicket, pwd)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> loading.setValue(false))
                .subscribe(this::onResetSuccess, this::onResetError));
    }

    private void onResetSuccess() {
        password.setValue("");
        confirm.setValue("");
        ruleRecent.setValue(RECENT_PASSED);
        if (fromSettings) {
            // token_version 已加 1，本机令牌同时失效：主动清除 token、user_json、is_guest
            repository.logout();
        }
        done.setValue(true);
    }

    private void onResetError(@NonNull Throwable e) {
        int errorCode = ApiException.codeOf(e);
        String message = ApiException.messageOf(e);
        if (errorCode == ApiException.PARAM_ERROR) {
            if (AccountTexts.SAME_PASSWORD.equals(message)) {
                ruleRecent.setValue(RECENT_FAILED);
                showToast(AccountTexts.SAME_PASSWORD);
            } else {
                expired.setValue(AccountTexts.TICKET_EXPIRED);
            }
            return;
        }
        showToast(message);
    }

    @Nullable
    static String passwordErrorOf(@Nullable String password) {
        // 输入满 8 位后仍不符合规则才提示，避免刚开始输入就报错（A09 恢复码方式设置新密码共用同一规则）
        return AccountTexts.passwordError(password);
    }

    private void updateConfirm() {
        String p = password.getValue();
        String c = confirm.getValue();
        boolean matched = c != null && !c.isEmpty() && c.equals(p);
        if (!Boolean.valueOf(matched).equals(confirmMatched.getValue())) {
            confirmMatched.setValue(matched);
        }
        updateConfirmHint();
    }

    private void updateConfirmHint() {
        String c = confirm.getValue();
        String hint;
        boolean error;
        if (c == null || c.isEmpty()) {
            hint = null;
            error = false;
        } else if (c.equals(password.getValue())) {
            hint = AccountTexts.CONFIRM_MATCHED;
            error = false;
        } else {
            hint = AccountTexts.CONFIRM_MISMATCH;
            error = true;
        }
        if (!Objects.equals(hint, confirmHint.getValue())) {
            confirmHint.setValue(hint);
        }
        if (!Boolean.valueOf(error).equals(confirmHintError.getValue())) {
            confirmHintError.setValue(error);
        }
    }

    private void updateCanSubmit() {
        Integer recent = ruleRecent.getValue();
        canSubmit.setValue(PasswordRules.isValid(password.getValue())
                && Boolean.TRUE.equals(confirmMatched.getValue())
                && (recent == null || recent != RECENT_FAILED)
                && !Boolean.TRUE.equals(loading.getValue()));
    }

    @VisibleForTesting
    void setClock(@NonNull LongSupplier clock) {
        this.clock = clock;
    }

    // ---------- LiveData ----------

    public MutableLiveData<String> getPassword() {
        return password;
    }

    public MutableLiveData<String> getConfirm() {
        return confirm;
    }

    public LiveData<Boolean> getPasswordVisible() {
        return passwordVisible;
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

    public LiveData<String> getPasswordError() {
        return passwordError;
    }

    public LiveData<Integer> getRuleRecent() {
        return ruleRecent;
    }

    public LiveData<Boolean> getConfirmMatched() {
        return confirmMatched;
    }

    public LiveData<String> getConfirmHint() {
        return confirmHint;
    }

    public LiveData<Boolean> getConfirmHintError() {
        return confirmHintError;
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

    public LiveData<String> getExpired() {
        return expired;
    }

    public LiveData<Boolean> getNavigateLogin() {
        return navigateLogin;
    }

    public boolean isFromSettings() {
        return fromSettings;
    }
}
