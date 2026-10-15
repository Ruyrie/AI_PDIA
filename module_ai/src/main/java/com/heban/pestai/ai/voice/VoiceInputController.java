package com.heban.pestai.ai.voice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.heban.pestai.ai.R;
import com.heban.pestai.ai.compose.VoiceComposer;
import com.heban.pestai.ai.util.AiMicPermission;
import com.heban.pestai.base.util.DimenUtils;

import java.io.File;

/**
 * 语音输入时「按住 说话」按钮的界面控制（由 {@link com.heban.pestai.ai.compose.ComposerBar} 创建，Q01 / Q02 共用）：
 * 按钮的触摸 / 点按、按钮文字与状态、{@link VoiceRecorder} 录音、{@link VoicePanel} 录音浮层，状态判断交给 {@link VoiceGesture}。
 * 文字 / 语音的切换（麦克风 / 键盘图标）与识别结果填入输入框由 ComposerBar 处理。
 * <ul>
 *     <li>按下：识别中忽略；游客弹出登录（{@link VoiceComposer#onVoicePress()}）；没有麦克风权限（如在系统设置中撤销了）时
 *     申请权限、本次不录音；否则振动一下并立即开始录音，按钮变为「松开 识别」（按下加深），
 *     显示浮层（音量条、计时、「松开 识别 · 上滑 取消」）；</li>
 *     <li>按住上滑超过 80dp：按钮与浮层都变为「松开 取消」（按钮 errorContainer、浮层红色），移回来恢复；
 *     50 秒起「还能说 N 秒」；60 秒自动结束并识别，之后的松开忽略；</li>
 *     <li>松开：取消区内静默丢弃；不足 1 秒丢弃并提示「说话时间太短」；否则按钮与浮层显示「识别中…」（按钮不可用），
 *     WAV 交给 ViewModel 上传识别；结束后按钮回到「按住 说话」；</li>
 *     <li>ACTION_CANCEL、页面 onStop、按钮从窗口移除或不可见（切换 Tab、切回文字）时取消录音并释放 AudioRecord，
 *     不会让麦克风一直开着；</li>
 *     <li>开启 TalkBack（触摸浏览）时按住手势难以完成：改为点按开始、再次点按结束并识别（录音中按钮为「结束并识别」），
 *     并播报状态变化。</li>
 * </ul>
 * 只在主线程使用。
 */
public final class VoiceInputController implements DefaultLifecycleObserver {

    /** 录音文件目录（cacheDir 下） */
    static final String VOICE_DIR = "ai_voice";
    /** 计时与倒计时的刷新间隔 */
    private static final long TICK_MS = 100;

    /** 「按住 说话」按钮 */
    private final TextView talk;
    private final LifecycleOwner owner;
    private final VoiceComposer composer;
    private final AiMicPermission permission;
    private final VoicePanel panel;
    private final VoiceGesture gesture;
    @Nullable
    private final AccessibilityManager accessibility;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::tick;
    private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View v) {
            render();
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View v) {
            cancelRecording();
            panel.release();
        }
    };

    /** 当前录音（录音中，或已结束、正在写完 WAV） */
    @Nullable
    private VoiceRecorder recorder;
    /** 每次开始录音加 1：忽略已取消的那次录音迟到的回调 */
    private int session;
    /** 录音已结束、WAV 还在写（马上交给 ViewModel 识别），显示「识别中…」 */
    private boolean finishing;
    /** ViewModel 正在识别 */
    private boolean recognizing;
    private boolean released;
    @StringRes
    private int actionLabel;

    private VoiceInputController(@NonNull TextView talk, @NonNull View anchor, @NonNull LifecycleOwner owner,
                                 @NonNull VoiceComposer composer, @NonNull AiMicPermission permission) {
        Context context = talk.getContext();
        this.talk = talk;
        this.owner = owner;
        this.composer = composer;
        this.permission = permission;
        this.panel = new VoicePanel(anchor);
        this.gesture = new VoiceGesture(DimenUtils.dp(context, VoiceGesture.CANCEL_DISTANCE_DP));
        this.accessibility = context.getSystemService(AccessibilityManager.class);
    }

    /**
     * 在宿主创建视图后调用（Fragment 传 viewLifecycleOwner）。
     *
     * @param talk   胶囊中的「按住 说话」按钮（语音输入时显示）
     * @param anchor 整个输入栏，浮层显示在它上方
     */
    @NonNull
    public static VoiceInputController attach(@NonNull TextView talk, @NonNull View anchor,
                                              @NonNull LifecycleOwner owner, @NonNull VoiceComposer composer,
                                              @NonNull AiMicPermission permission) {
        VoiceInputController controller = new VoiceInputController(talk, anchor, owner, composer, permission);
        controller.setup();
        return controller;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setup() {
        // 触摸：按住说话；点按（TalkBack 双击、键盘、开关控制）：开始 / 结束，见 onTalkClick
        talk.setOnTouchListener(this::onTouch);
        talk.setOnClickListener(v -> onTalkClick());
        talk.addOnAttachStateChangeListener(attachListener);
        // 无障碍按「按钮」朗读（TextView 默认只读文字）；点按的操作名称见 updateAccessibilityAction
        ViewCompat.setAccessibilityDelegate(talk, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        owner.getLifecycle().addObserver(this);
        composer.getVoiceRecognizing().observe(owner, value -> {
            recognizing = Boolean.TRUE.equals(value);
            render();
        });
        render();
    }

    /** 页面停止：取消录音（旋转屏幕、切到后台、来电） */
    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        cancelRecording();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        release();
    }

    /**
     * 宿主销毁视图时调用（可重复调用）：取消录音、移除浮层与监听。
     * 已结束、正在写完的那段录音仍会交给 ViewModel 识别（旋转屏幕后结果填入新页面）。
     */
    public void release() {
        if (released) {
            return;
        }
        cancelRecording();
        released = true;
        handler.removeCallbacks(ticker);
        talk.setOnTouchListener(null);
        talk.setOnClickListener(null);
        talk.removeOnAttachStateChangeListener(attachListener);
        owner.getLifecycle().removeObserver(this);
        panel.release();
    }

    /**
     * 识别结果已填入输入框、输入框获得焦点后由 ComposerBar 调用：开启 TalkBack 时播报「已填入识别结果」。
     *
     * @param source 发出播报的可见视图（此时「按住 说话」已隐藏，隐藏的视图不能播报）
     */
    public void announceFilled(@NonNull View source) {
        announce(source, R.string.ai_voice_a11y_done);
    }

    // ==================== 触摸与点按 ====================

    @SuppressLint("ClickableViewAccessibility")
    private boolean onTouch(@NonNull View v, @NonNull MotionEvent ev) {
        if (isTouchExploration()) {
            // TalkBack：交给默认处理，双击产生点按（onTalkClick）
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.drawableHotspotChanged(ev.getX(), ev.getY());
                v.setPressed(true);
                ViewParent parent = v.getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                if (gesture.isRecording()) {
                    // 点按模式录音中（关闭了 TalkBack）：按下即结束
                    handle(gesture.release(now()));
                } else {
                    begin(ev.getRawY(), false);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (isHolding() && gesture.move(ev.getRawY())) {
                    render();
                }
                return true;
            case MotionEvent.ACTION_UP:
                v.setPressed(false);
                if (isHolding()) {
                    handle(gesture.release(now()));
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                if (isHolding()) {
                    cancelRecording();
                }
                return true;
            default:
                return true;
        }
    }

    /** 点按（TalkBack 双击等）：没有在录音时开始，录音中结束并识别 */
    private void onTalkClick() {
        if (gesture.isRecording()) {
            handle(gesture.release(now()));
            return;
        }
        if (begin(0f, true)) {
            announce(talk, R.string.ai_voice_a11y_started);
        }
    }

    /** 按住手势录音中（不是点按模式） */
    private boolean isHolding() {
        return gesture.isRecording() && !gesture.isToggle();
    }

    // ==================== 录音 ====================

    /**
     * 开始录音。
     *
     * @return 是否已开始（识别中、游客、没有权限时为 false）
     */
    private boolean begin(float downY, boolean toggle) {
        if (released || finishing || recognizing) {
            return false;
        }
        if (!composer.onVoicePress()) {
            return false;
        }
        if (!permission.isGranted()) {
            // 语音输入时权限被撤销：申请权限，授权后提示重新按住（ViewModel 处理）
            permission.request();
            return false;
        }
        talk.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        File dir = new File(talk.getContext().getCacheDir(), VOICE_DIR);
        File file = new File(dir, "voice_" + System.currentTimeMillis() + ".wav");
        gesture.start(now(), downY, toggle);
        final int id = ++session;
        recorder = VoiceRecorder.start(file, new VoiceRecorder.Listener() {
            @Override
            public void onLevel(float level) {
                if (id == session && gesture.isRecording()) {
                    panel.pushLevel(level);
                }
            }

            @Override
            public void onFinished(@NonNull File wav) {
                onRecorded(id, wav);
            }

            @Override
            public void onError() {
                onRecordError(id);
            }
        });
        panel.reset();
        render();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);
        return true;
    }

    /** 每 100ms：刷新计时 / 倒计时，到 60 秒自动结束；按钮不可见（切换 Tab、切回文字）时取消 */
    private void tick() {
        if (!gesture.isRecording()) {
            return;
        }
        if (!talk.isShown()) {
            cancelRecording();
            return;
        }
        VoiceGesture.Result result = gesture.tick(now());
        if (result != VoiceGesture.Result.NONE) {
            handle(result);
            return;
        }
        render();
        handler.postDelayed(ticker, TICK_MS);
    }

    /** 松开 / 第二次点按 / 自动结束的处理 */
    private void handle(@NonNull VoiceGesture.Result result) {
        switch (result) {
            case CANCEL:
                discard();
                announce(talk, R.string.ai_voice_a11y_cancelled);
                break;
            case TOO_SHORT:
                discard();
                composer.onVoiceTooShort();
                break;
            case RECOGNIZE:
                handler.removeCallbacks(ticker);
                if (recorder == null) {
                    // 录音已出错结束（已提示），没有可识别的文件
                    render();
                    break;
                }
                // 写完 WAV 后回调 onRecorded
                finishing = true;
                recorder.stop();
                render();
                announce(talk, R.string.ai_voice_a11y_recognizing);
                break;
            default:
                break;
        }
    }

    /** 录音完成：交给 ViewModel 识别（页面已销毁时也交给它：旋转屏幕后结果填入新页面） */
    private void onRecorded(int id, @NonNull File wav) {
        if (id == session) {
            finishing = false;
            recorder = null;
        }
        composer.recognizeSpeech(wav);
        if (!released) {
            render();
        }
    }

    private void onRecordError(int id) {
        if (released || id != session) {
            return;
        }
        gesture.cancel();
        finishing = false;
        recorder = null;
        handler.removeCallbacks(ticker);
        render();
        composer.onVoiceUnavailable();
    }

    /** ACTION_CANCEL、onStop、移除视图、按钮不可见：取消正在进行的录音（静默丢弃） */
    private void cancelRecording() {
        if (gesture.isRecording()) {
            gesture.cancel();
            discard();
        }
    }

    /** 丢弃录音：停止并删除文件，关闭浮层 */
    private void discard() {
        handler.removeCallbacks(ticker);
        VoiceRecorder current = recorder;
        recorder = null;
        if (current != null) {
            current.cancel();
        }
        if (!released) {
            render();
        }
    }

    // ==================== 界面 ====================

    /** 按录音 / 识别状态显示浮层与按钮 */
    private void render() {
        if (released) {
            return;
        }
        Context context = talk.getContext();
        boolean busy = finishing || recognizing;
        if (gesture.isRecording()) {
            long now = now();
            panel.showRecording(VoiceGesture.formatTimer(gesture.elapsedMs(now)), hintText(context, now),
                    gesture.isCancelArmed());
        } else if (busy) {
            panel.showRecognizing(context.getString(R.string.ai_voice_recognizing));
        } else {
            panel.hide();
        }
        renderButton(context, busy);
        updateAccessibilityAction();
    }

    /**
     * 按钮：文字见 {@link VoiceGesture#label(boolean)}；取消区 activated（errorContainer 配色，见 ai_talk_container）；
     * 识别中不可用（文字变淡，TalkBack 读「已停用」）。按下加深由触摸时的 pressed 状态处理。
     */
    private void renderButton(@NonNull Context context, boolean busy) {
        String text = context.getString(labelText(gesture.label(busy)));
        if (!TextUtils.equals(talk.getText(), text)) {
            talk.setText(text);
        }
        boolean cancel = gesture.isCancelArmed();
        if (talk.isActivated() != cancel) {
            talk.setActivated(cancel);
        }
        // 录音中一直可用（自动结束前不能让正在按住的手势失效）
        boolean enabled = gesture.isRecording() || !busy;
        if (talk.isEnabled() != enabled) {
            talk.setEnabled(enabled);
        }
    }

    @StringRes
    private static int labelText(@NonNull VoiceGesture.Label label) {
        switch (label) {
            case RELEASE_TO_RECOGNIZE:
                return R.string.ai_voice_talk_release;
            case RELEASE_TO_CANCEL:
                return R.string.ai_voice_hint_cancel;
            case TAP_TO_STOP:
                return R.string.ai_voice_action_stop;
            case RECOGNIZING:
                return R.string.ai_voice_recognizing;
            case HOLD_TO_TALK:
            default:
                return R.string.ai_voice_talk;
        }
    }

    @NonNull
    private String hintText(@NonNull Context context, long now) {
        switch (gesture.hint(now)) {
            case RELEASE_TO_CANCEL:
                return context.getString(R.string.ai_voice_hint_cancel);
            case COUNTDOWN:
                return context.getString(R.string.ai_voice_countdown, gesture.remainingSeconds(now));
            case TAP_TO_STOP:
                return context.getString(R.string.ai_voice_hint_toggle);
            case RELEASE_TO_RECOGNIZE:
            default:
                return context.getString(R.string.ai_voice_hint);
        }
    }

    // ==================== 无障碍 ====================

    private boolean isTouchExploration() {
        return accessibility != null && accessibility.isEnabled() && accessibility.isTouchExplorationEnabled();
    }

    /** 无障碍「双击即可…」的操作名称：录音中为「结束并识别」，否则「开始语音输入」 */
    private void updateAccessibilityAction() {
        int label = gesture.isRecording() ? R.string.ai_voice_action_stop : R.string.ai_voice_action_start;
        if (label != actionLabel) {
            actionLabel = label;
            ViewCompat.replaceAccessibilityAction(talk, AccessibilityActionCompat.ACTION_CLICK,
                    talk.getContext().getString(label), null);
        }
    }

    /**
     * 开启 TalkBack 时播报状态变化（开始录音、识别中、已填入、已取消）；
     * API 36 起 announceForAccessibility 标记为过时，但仍是播报临时状态最直接的方式（浮层不获得无障碍焦点）。
     */
    @SuppressWarnings("deprecation")
    private void announce(@NonNull View source, @StringRes int text) {
        if (isTouchExploration()) {
            source.announceForAccessibility(source.getContext().getString(text));
        }
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }
}
