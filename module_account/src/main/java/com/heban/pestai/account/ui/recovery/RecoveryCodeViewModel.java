package com.heban.pestai.account.ui.recovery;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.account.util.PhoneRules;
import com.heban.pestai.account.util.RecoveryCodeRules;
import com.heban.pestai.account.util.SmsCountdown;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.network.ApiException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A12 账号恢复码（需要登录）。
 * <ul>
 *     <li>进入时 GET user/recovery-code 查询状态：未生成 / 已生成（日期），以及 verify_required；</li>
 *     <li>verify_required 为 true（或查询失败）：验证当前手机号（RECOVERY 验证码由服务器发往当前手机号）→「生成恢复码」；
 *     为 false（10 分钟内刚通过短信验证）：隐藏验证码区域，显示「你刚刚已通过短信验证，可以直接生成」，生成请求不带 code；
 *     直接生成时服务器返回 40001「请先验证当前手机号」（窗口刚好过期）则自动切换为需要验证码的界面并提示；</li>
 *     <li>已生成（或状态未知）时「重新生成恢复码」先确认「重新生成后，旧恢复码将立即失效」；</li>
 *     <li>结果：大号等宽字显示 ABCD-EFGH-JKLM（明文只返回这一次），「复制」后提示「已复制，请妥善保存」；
 *     勾选「我已抄写或截图保存」后「完成」可点；未勾选就返回时提醒「恢复码只显示这一次，确定已保存？」。</li>
 * </ul>
 * 引导模式（{@link #init(boolean)} 传 true，新用户完善资料之后）：「稍后再说」/「完成」/ 返回键都发出 {@link #getGoMain()}
 * （清空任务栈进入首页）；账号刚创建不可能已有恢复码，状态查询失败时也不再确认；生成失败时在按钮上方显示原因，按钮变为「重试」。
 */
public class RecoveryCodeViewModel extends BaseViewModel {

    public static final long SEND_WINDOW_MS = 1000;
    public static final long CLICK_WINDOW_MS = 500;

    private final AccountRepository repository;

    @Nullable
    private final String currentPhone;
    private final String verifyTitle;

    /** 引导模式（from_register） */
    private final MutableLiveData<Boolean> guide = new MutableLiveData<>(false);

    /** 服务器返回的状态；null 表示查询中或查询失败 */
    private final MutableLiveData<RecoveryCodeVO> status = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> statusFailed = new MutableLiveData<>(false);
    private final MediatorLiveData<String> statusText = new MediatorLiveData<>(AccountTexts.RECOVERY_STATUS_LOADING);
    private final LiveData<Boolean> generated;

    /**
     * 生成是否需要短信验证码：null 表示查询中（两种区域都不显示、按钮不可点）；查询失败按需要处理；
     * 免验证直接生成被服务器拒绝后固定为 true
     */
    private final MutableLiveData<Boolean> verifyRequired = new MutableLiveData<>(null);
    private final LiveData<Boolean> needCode;
    private final LiveData<Boolean> direct;
    /** 直接生成被拒后已切换为验证码界面，较晚的状态结果不再改回免验证 */
    private boolean verifyForced;

    private final MutableLiveData<String> code = new MutableLiveData<>("");
    private final SmsCountdown countdown = new SmsCountdown();
    private final MutableLiveData<Boolean> sendingCode = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSendCode = new MediatorLiveData<>(false);
    private final MutableLiveData<String> sentHint = new MutableLiveData<>(null);
    /** 生成返回 40103 后的验证码错误提示，修改验证码即清除 */
    private final MutableLiveData<String> codeError = new MutableLiveData<>(null);
    private final MediatorLiveData<String> codeHint = new MediatorLiveData<>(null);
    private final MediatorLiveData<Boolean> codeHintError = new MediatorLiveData<>(false);
    private final MutableLiveData<Boolean> generating = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canGenerate = new MediatorLiveData<>(false);
    /** 引导模式生成失败的原因（按钮上方显示，按钮文字变为「重试」）；再次生成时清除 */
    private final MutableLiveData<String> generateError = new MutableLiveData<>(null);

    /** 刚生成的恢复码（ABCD-EFGH-JKLM）；非空即显示结果 */
    private final MutableLiveData<String> recoveryCode = new MutableLiveData<>(null);
    private final LiveData<Boolean> showResult;
    /** 「我已抄写或截图保存」 */
    private final MutableLiveData<Boolean> saved = new MutableLiveData<>(false);

    private final SingleLiveEvent<Void> showRegenerateConfirm = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> showLeaveConfirm = new SingleLiveEvent<>();
    private final SingleLiveEvent<String> copyCode = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> finish = new SingleLiveEvent<>();
    /** 引导模式离开：清空任务栈进入首页 */
    private final SingleLiveEvent<Void> goMain = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> focusCode = new SingleLiveEvent<>();
    private final PublishSubject<Boolean> sendClicks = PublishSubject.create();
    private final PublishSubject<Boolean> generateClicks = PublishSubject.create();
    private final PublishSubject<Boolean> confirmClicks = PublishSubject.create();

    private boolean initialized;

    @Inject
    public RecoveryCodeViewModel(AccountRepository repository) {
        this.repository = repository;
        currentPhone = repository.currentPhone();
        verifyTitle = AccountTexts.verifyCurrentPhone(currentPhone);

        generated = Transformations.map(status, vo -> vo != null && vo.isGenerated());
        showResult = Transformations.map(recoveryCode, value -> value != null && !value.isEmpty());
        needCode = Transformations.map(verifyRequired, v -> Boolean.TRUE.equals(v));
        direct = Transformations.map(verifyRequired, v -> Boolean.FALSE.equals(v));
        statusText.addSource(status, v -> updateStatusText());
        statusText.addSource(statusFailed, v -> updateStatusText());

        canSendCode.addSource(countdown.getCounting(), v -> updateCanSendCode());
        canSendCode.addSource(sendingCode, v -> updateCanSendCode());
        codeHint.addSource(sentHint, v -> updateCodeHint());
        codeHint.addSource(codeError, v -> updateCodeHint());
        canGenerate.addSource(code, v -> {
            if (codeError.getValue() != null) {
                codeError.setValue(null);
            }
            updateCanGenerate();
        });
        canGenerate.addSource(generating, v -> updateCanGenerate());
        canGenerate.addSource(recoveryCode, v -> updateCanGenerate());
        canGenerate.addSource(verifyRequired, v -> updateCanGenerate());

        add(sendClicks.throttleFirst(SEND_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSendCode()));
        add(generateClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doGenerateClick()));
        add(confirmClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doGenerate()));

        // 在构造时查询，旋转屏幕不会重复请求
        loadStatus();
    }

    /** 页面传入 Intent 参数 from_register；只在首次创建时生效 */
    public void init(boolean fromRegister) {
        if (initialized) {
            return;
        }
        initialized = true;
        guide.setValue(fromRegister);
    }

    // ---------- 状态 ----------

    /** GET user/recovery-code */
    public void loadStatus() {
        statusFailed.setValue(false);
        add(repository.recoveryCodeStatus()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(vo -> {
                    // 生成成功后状态已在本地更新，较早发出的查询结果不再覆盖
                    if (!hasResult()) {
                        status.setValue(vo);
                        setVerifyRequired(verifyForced || vo.isVerifyRequired());
                    }
                }, e -> {
                    statusFailed.setValue(true);
                    // 不知道是否刚验证过：按需要验证码处理（现有流程）
                    if (verifyRequired.getValue() == null) {
                        setVerifyRequired(true);
                    }
                    showToast(ApiException.messageOf(e));
                }));
    }

    private void updateStatusText() {
        RecoveryCodeVO vo = status.getValue();
        String text;
        if (vo != null) {
            text = vo.isGenerated()
                    ? AccountTexts.recoveryStatusGenerated(toLocalDate(vo.getCreatedAt()))
                    : AccountTexts.RECOVERY_STATUS_NONE;
        } else if (Boolean.TRUE.equals(statusFailed.getValue())) {
            text = AccountTexts.RECOVERY_STATUS_UNKNOWN;
        } else {
            text = AccountTexts.RECOVERY_STATUS_LOADING;
        }
        if (!Objects.equals(text, statusText.getValue())) {
            statusText.setValue(text);
        }
    }

    @Nullable
    static LocalDate toLocalDate(@Nullable Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void setVerifyRequired(boolean required) {
        if (!Boolean.valueOf(required).equals(verifyRequired.getValue())) {
            verifyRequired.setValue(required);
        }
    }

    // ---------- 验证与生成 ----------

    /** 「获取验证码」：发往当前手机号 */
    public void sendCode() {
        sendClicks.onNext(Boolean.TRUE);
    }

    /** 「生成恢复码」/「重新生成恢复码」/「重试」 */
    public void generate() {
        generateClicks.onNext(Boolean.TRUE);
    }

    /** 重新生成确认对话框「重新生成」 */
    public void confirmRegenerate() {
        confirmClicks.onNext(Boolean.TRUE);
    }

    private void doSendCode() {
        if (countdown.isCounting() || Boolean.TRUE.equals(sendingCode.getValue()) || hasResult()) {
            return;
        }
        sendingCode.setValue(true);
        add(repository.sendRecoveryCodeSms()
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

    /**
     * 已明确「未生成」时直接生成；已生成或状态未知（查询失败）时先确认，避免旧码在不知情时失效。
     * 引导模式下账号刚创建，不可能已有恢复码，状态未知时也直接生成。
     */
    private void doGenerateClick() {
        if (!isReady()) {
            return;
        }
        RecoveryCodeVO vo = status.getValue();
        boolean knownNone = vo != null && !vo.isGenerated();
        boolean newAccount = vo == null && isGuide();
        if (knownNone || newAccount) {
            doGenerate();
        } else {
            showRegenerateConfirm.call();
        }
    }

    private void doGenerate() {
        if (!isReady()) {
            return;
        }
        final boolean withCode = Boolean.TRUE.equals(verifyRequired.getValue());
        generateError.setValue(null);
        generating.setValue(true);
        add(repository.generateRecoveryCode(withCode ? code.getValue() : null)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> generating.setValue(false))
                .subscribe(this::onGenerated, e -> onGenerateFailed(e, withCode)));
    }

    private void onGenerateFailed(@NonNull Throwable e, boolean withCode) {
        int errorCode = ApiException.codeOf(e);
        if (!withCode && (errorCode == ApiException.PARAM_ERROR || errorCode == ApiException.SMS_CODE_INVALID)) {
            // 免验证直接生成被拒（「请先验证当前手机号」：10 分钟窗口刚好过期）→ 切换为需要验证码的界面
            verifyForced = true;
            setVerifyRequired(true);
            showToast(AccountTexts.RECOVERY_VERIFY_EXPIRED);
        } else if (withCode && errorCode == ApiException.SMS_CODE_INVALID) {
            code.setValue("");
            codeError.setValue(AccountTexts.CODE_EXPIRED);
        } else if (isGuide()) {
            generateError.setValue(ApiException.messageOf(e));
        } else {
            showToast(ApiException.messageOf(e));
        }
    }

    private void onGenerated(@NonNull RecoveryCodeVO vo) {
        String formatted = RecoveryCodeRules.format(vo.getRecoveryCode());
        if (formatted.isEmpty()) {
            String message = ApiException.defaultMessage(ApiException.SERVER_ERROR);
            if (isGuide()) {
                generateError.setValue(message);
            } else {
                showToast(message);
            }
            return;
        }
        countdown.stop();
        code.setValue("");
        sentHint.setValue(null);
        generateError.setValue(null);
        RecoveryCodeVO updated = new RecoveryCodeVO();
        updated.setGenerated(true);
        updated.setCreatedAt(vo.getCreatedAt() != null ? vo.getCreatedAt() : new Date());
        status.setValue(updated);
        statusFailed.setValue(false);
        saved.setValue(false);
        recoveryCode.setValue(formatted);
    }

    // ---------- 结果 ----------

    /** 「复制」：页面写入剪贴板，提示「已复制，请妥善保存」 */
    public void copy() {
        String value = recoveryCode.getValue();
        if (value == null || value.isEmpty()) {
            return;
        }
        copyCode.setValue(value);
        showToast(AccountTexts.RECOVERY_COPIED);
    }

    /** 「完成」：勾选「我已抄写或截图保存」后才可点；引导模式进入首页 */
    public void done() {
        if (Boolean.TRUE.equals(saved.getValue())) {
            leavePage();
        }
    }

    /** 引导模式「稍后再说」：等同返回键（生成中不响应，按钮同时置灰） */
    public void later() {
        if (Boolean.TRUE.equals(generating.getValue())) {
            return;
        }
        back();
    }

    /** 返回键 / 顶栏返回：结果已显示但未勾选保存时先提醒，否则直接离开（引导模式进入首页） */
    public void back() {
        if (hasResult() && !Boolean.TRUE.equals(saved.getValue())) {
            showLeaveConfirm.call();
        } else {
            leavePage();
        }
    }

    /** 离开提醒中确认离开 */
    public void leave() {
        leavePage();
    }

    private void leavePage() {
        if (isGuide()) {
            goMain.call();
        } else {
            finish.call();
        }
    }

    // ---------- 校验 ----------

    private boolean isGuide() {
        return Boolean.TRUE.equals(guide.getValue());
    }

    private boolean hasResult() {
        String value = recoveryCode.getValue();
        return value != null && !value.isEmpty();
    }

    /** 状态已知（查询中不可点）、未在生成、没有结果；需要验证码时还要 6 位验证码 */
    private boolean isReady() {
        Boolean required = verifyRequired.getValue();
        if (required == null || hasResult() || Boolean.TRUE.equals(generating.getValue())) {
            return false;
        }
        return !required || PhoneRules.isSmsCode(code.getValue());
    }

    private void updateCanSendCode() {
        canSendCode.setValue(!countdown.isCounting() && !Boolean.TRUE.equals(sendingCode.getValue()));
    }

    private void updateCanGenerate() {
        boolean ready = isReady();
        if (!Boolean.valueOf(ready).equals(canGenerate.getValue())) {
            canGenerate.setValue(ready);
        }
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

    /** 引导模式：标题「保存账号恢复码」、说明「账号已创建…」、显示「稍后再说」、隐藏当前状态行 */
    public LiveData<Boolean> getGuide() {
        return guide;
    }

    /** 「验证当前手机号 138****5678」 */
    public String getVerifyTitle() {
        return verifyTitle;
    }

    public LiveData<RecoveryCodeVO> getStatus() {
        return status;
    }

    /** 「当前状态：未生成」/「当前状态：已生成（2026年10月13日）」 */
    public LiveData<String> getStatusText() {
        return statusText;
    }

    /** 已生成：按钮为「重新生成恢复码」 */
    public LiveData<Boolean> getGenerated() {
        return generated;
    }

    /** 显示「验证当前手机号 + 验证码」区域 */
    public LiveData<Boolean> getNeedCode() {
        return needCode;
    }

    /** 免验证：显示「你刚刚已通过短信验证，可以直接生成」 */
    public LiveData<Boolean> getDirect() {
        return direct;
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

    public LiveData<Boolean> getGenerating() {
        return generating;
    }

    public LiveData<Boolean> getCanGenerate() {
        return canGenerate;
    }

    /** 引导模式生成失败的原因；非空时按钮文字为「重试」 */
    public LiveData<String> getGenerateError() {
        return generateError;
    }

    public LiveData<String> getRecoveryCode() {
        return recoveryCode;
    }

    public LiveData<Boolean> getShowResult() {
        return showResult;
    }

    public MutableLiveData<Boolean> getSaved() {
        return saved;
    }

    public LiveData<Void> getShowRegenerateConfirm() {
        return showRegenerateConfirm;
    }

    public LiveData<Void> getShowLeaveConfirm() {
        return showLeaveConfirm;
    }

    /** 需要写入剪贴板的恢复码 */
    public LiveData<String> getCopyCode() {
        return copyCode;
    }

    /** 普通模式离开：关闭页面 */
    public LiveData<Void> getFinish() {
        return finish;
    }

    /** 引导模式离开：清空任务栈进入首页（RouterPath.MAIN） */
    public LiveData<Void> getGoMain() {
        return goMain;
    }

    public LiveData<Void> getFocusCode() {
        return focusCode;
    }
}
