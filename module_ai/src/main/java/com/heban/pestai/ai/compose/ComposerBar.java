package com.heban.pestai.ai.compose;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Build;
import android.text.Layout;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LifecycleOwner;

import com.bumptech.glide.RequestManager;
import com.heban.pestai.ai.R;
import com.heban.pestai.ai.databinding.ViewAiComposerBinding;
import com.heban.pestai.ai.util.AiMicPermission;
import com.heban.pestai.ai.voice.VoiceInputController;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.base.util.RxClick;

/**
 * Q01 / Q02 共用输入栏（include 布局 view_ai_composer.xml）的界面部分，两个页面只通过 {@link #setup} 接入。
 * <p>布局结构：上方为待发送图片缩略图条（{@link ImageQueueStrip}，没有图片时隐藏）；下方为圆角胶囊
 * （最小高 52dp，surfaceContainerHigh 底），两种输入方式（像微信）：</p>
 * <ul>
 *     <li>文字：相机（直接拍照）· 多行输入框（最多 5 行）· 麦克风（切换到语音）·「+」（直接打开相册多选）·
 *     36dp 圆形发送按钮（Q02 生成中为停止按钮）；单行时整行垂直居中（字体放大时文字与图标仍在同一水平线），
 *     文字换行后按钮贴底对齐；</li>
 *     <li>语音：「按住 说话」（占相机与输入框的位置）· 键盘（与麦克风同一位置，切换回文字）；生成中另有停止按钮。</li>
 * </ul>
 * <p>文字、提示语与相机 /「+」/ 发送的点击由数据绑定处理（宿主布局通过 {@code app:composer="@{vm}"}、
 * {@code app:defaultHint} 传入）；本类负责：</p>
 * <ul>
 *     <li>绑定缩略图条；按 {@link ComposerButtons} 显示 / 隐藏按钮、互换输入框与「按住 说话」
 *     （150ms 淡入淡出，不做位置过渡）；已满 9 张时相机与「+」变淡；发送 ↔ 停止图标切换；</li>
 *     <li>麦克风 / 键盘的点按（{@link VoiceComposer#onToggleInputMode}）：切到语音时收起键盘、清除焦点
 *     （文字保留）；第一次没有麦克风权限时先申请（{@link AiMicPermission}，结果交给 ViewModel）；
 *     切回文字时输入框获得焦点并弹出键盘；</li>
 *     <li>「按住 说话」的录音与浮层（{@link VoiceInputController}）；识别成功后（ViewModel 已追加文字并临时切到文字）
 *     输入框获得焦点、光标移到末尾并弹出键盘；</li>
 *     <li>判断按下位置是否在输入栏内（宿主据此不收起键盘）。</li>
 * </ul>
 */
public final class ComposerBar {

    /** 按钮出现 / 消失的过渡时长 */
    static final long TRANSITION_MS = 150;
    /** 已满 9 张时相机与「+」的透明度（仍可点击，点击提示「最多只能添加 9 张图片」） */
    static final float DIMMED_ALPHA = 0.38f;

    private final ViewAiComposerBinding binding;
    private final VoiceComposer composer;
    private final AiMicPermission micPermission;
    private final VoiceInputController voice;
    /** 当前显示的按钮状态（null：还没有显示过，第一次不做动画） */
    @Nullable
    private ComposerButtons shown;
    @Nullable
    private Animator iconAnimator;
    /** 等待窗口获得焦点后弹出键盘（从相机 / 照片选择器返回、页面重新可见时窗口可能还没有焦点） */
    @Nullable
    private ViewTreeObserver.OnWindowFocusChangeListener focusWaiter;
    private boolean released;
    /** 输入框在布局文件中的上下内边距（单行文字居中时在它们之间挪动，总和不变） */
    private final int inputPaddingTop;
    private final int inputPaddingBottom;
    private final Rect glyphBounds = new Rect();

    private ComposerBar(@NonNull ViewAiComposerBinding binding, @NonNull VoiceComposer composer,
                        @NonNull AiMicPermission micPermission, @NonNull VoiceInputController voice) {
        this.binding = binding;
        this.composer = composer;
        this.micPermission = micPermission;
        this.voice = voice;
        EditText input = binding.aiComposerInput;
        this.inputPaddingTop = input.getPaddingTop();
        this.inputPaddingBottom = input.getPaddingBottom();
        // 行框只由字体本身的 ascent / descent 决定：不按回退的中文字体撑高行距，也不用 Android 15 起按系统语言保证的最小行高。
        // 否则提示语（单独排版）与正文的行框不同，centerInputText() 按正文行框算出的位移会把提示语移偏
        input.setFallbackLineSpacing(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            input.setLocalePreferredLineHeightForMinimumUsed(false);
        }
    }

    /**
     * 在宿主创建视图后调用（Fragment 传 viewLifecycleOwner）。
     *
     * @param binding       宿主布局中 include 的输入栏
     * @param composer      页面 ViewModel（输入文字、图片队列、按钮状态、输入方式与语音识别）
     * @param micPermission 宿主 onCreate 中注册的麦克风权限（本类设置它的结果监听）
     */
    @NonNull
    public static <C extends ImageComposer & VoiceComposer> ComposerBar setup(
            @NonNull ViewAiComposerBinding binding, @NonNull LifecycleOwner owner, @NonNull RequestManager glide,
            @NonNull C composer, @NonNull AiMicPermission micPermission) {
        ImageQueueStrip.setup(binding.aiComposerQueue, glide, owner, composer);
        VoiceInputController voice = VoiceInputController.attach(binding.aiComposerTalk, binding.getRoot(), owner,
                composer, micPermission);
        ComposerBar bar = new ComposerBar(binding, composer, micPermission, voice);
        // 麦克风与键盘在同一位置：点按来回切换
        RxClick.throttle(binding.aiComposerMic, v -> bar.toggleInputMode());
        RxClick.throttle(binding.aiComposerKeyboard, v -> bar.toggleInputMode());
        micPermission.setListener(composer::onMicPermissionResult);
        // 单行居中、换行贴底，单行时按实际字形把文字移到正中：行数变化（输入、粘贴、识别填入、宽度变化）都会触发重新布局
        binding.aiComposerInput.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            bar.updatePillGravity();
            bar.centerInputText();
        });
        composer.getComposerButtons().observe(owner, bar::render);
        composer.getVoiceText().observe(owner, bar::onVoiceText);
        return bar;
    }

    @NonNull
    public EditText getInput() {
        return binding.aiComposerInput;
    }

    /** 整个输入栏（缩略图条 + 胶囊），用作 Snackbar 锚点 */
    @NonNull
    public View getRoot() {
        return binding.getRoot();
    }

    /** 按下位置在输入栏（缩略图条或胶囊）内：宿主不按「点击输入框外收起键盘」处理，避免键盘闪动 */
    public boolean contains(@NonNull MotionEvent ev) {
        View root = binding.getRoot();
        if (!root.isShown()) {
            return false;
        }
        Rect rect = new Rect();
        return root.getGlobalVisibleRect(rect) && rect.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    /**
     * 输入框获得焦点、光标移到末尾并弹出键盘（切回文字、识别成功、Q01 选好照片后）。
     * 输入框不可见（语音输入、页面隐藏）或不可获得焦点（游客）时不处理；窗口还没有焦点时等获得焦点后再弹出键盘。
     */
    public void focusInput() {
        EditText input = binding.aiComposerInput;
        if (released || !input.isShown() || !input.isFocusable()) {
            return;
        }
        input.requestFocus();
        input.setSelection(input.length());
        removeFocusWaiter();
        if (input.hasWindowFocus()) {
            input.post(this::showKeyboard);
            return;
        }
        focusWaiter = hasFocus -> {
            if (hasFocus) {
                removeFocusWaiter();
                input.post(this::showKeyboard);
            }
        };
        input.getViewTreeObserver().addOnWindowFocusChangeListener(focusWaiter);
    }

    /** 收起键盘并清除输入框焦点（切到语音、离开页面） */
    public void hideKeyboard() {
        removeFocusWaiter();
        EditText input = binding.aiComposerInput;
        input.clearFocus();
        KeyboardUtils.hide(input);
    }

    /** 宿主销毁视图时调用：取消录音（移除录音浮层），结束图标动画与按钮过渡 */
    public void release() {
        released = true;
        removeFocusWaiter();
        voice.release();
        micPermission.setListener(null);
        if (iconAnimator != null) {
            iconAnimator.cancel();
            iconAnimator = null;
        }
        TransitionManager.endTransitions(binding.aiComposerPill);
    }

    // ==================== 输入方式 ====================

    /**
     * 点按麦克风 / 键盘：游客由 ViewModel 弹出登录；需要麦克风权限时申请（授权后 ViewModel 切到语音）；
     * 切回文字时输入框获得焦点并弹出键盘（切到语音时收起键盘在 {@link #render} 中处理）。
     */
    private void toggleInputMode() {
        if (released) {
            return;
        }
        boolean wasVoice = isVoiceMode();
        if (composer.onToggleInputMode(micPermission.isGranted())) {
            micPermission.request();
            return;
        }
        // LiveData 同步分发：此时按钮已重新显示，输入框已可见
        if (wasVoice && !isVoiceMode()) {
            focusInput();
        }
    }

    private boolean isVoiceMode() {
        return Boolean.TRUE.equals(composer.getVoiceMode().getValue());
    }

    /**
     * 识别成功：ViewModel 已把文字追加到输入框并临时切到文字（输入框已可见）。
     * 双向绑定下一帧才把文字写入输入框，先执行绑定，光标才能移到新文字的末尾。
     */
    private void onVoiceText(@Nullable String text) {
        if (released || text == null || text.isEmpty()) {
            return;
        }
        binding.executePendingBindings();
        focusInput();
        voice.announceFilled(binding.aiComposerInput);
    }

    // ==================== 按钮 ====================

    private void render(@Nullable ComposerButtons buttons) {
        if (buttons == null || buttons.equals(shown)) {
            return;
        }
        ComposerButtons old = shown;
        shown = buttons;
        if (old != null && !old.isVoiceMode() && buttons.isVoiceMode()) {
            // 切到语音：先收起键盘、清除焦点，输入框随后隐藏（文字保留，切回文字时再显示）
            hideKeyboard();
        }
        ViewGroup pill = binding.aiComposerPill;
        // 先结束上一次还没播完的过渡，避免叠加
        TransitionManager.endTransitions(pill);
        if (old != null && old.visibilityDiffers(buttons) && pill.isLaidOut()) {
            TransitionManager.beginDelayedTransition(pill, createTransition());
        }
        setShown(binding.aiComposerCamera, buttons.isCameraVisible());
        setShown(binding.aiComposerInput, buttons.isInputVisible());
        setShown(binding.aiComposerTalk, buttons.isTalkVisible());
        setShown(binding.aiComposerMic, buttons.isMicVisible());
        setShown(binding.aiComposerKeyboard, buttons.isKeyboardVisible());
        setShown(binding.aiComposerAlbum, buttons.isAlbumVisible());
        setShown(binding.aiComposerAction, buttons.isActionVisible());
        float alpha = buttons.isAddDimmed() ? DIMMED_ALPHA : 1f;
        binding.aiComposerCamera.setAlpha(alpha);
        binding.aiComposerAlbum.setAlpha(alpha);
        binding.aiComposerAction.setEnabled(buttons.isActionEnabled());
        updatePillGravity();
        if (old == null || old.isStop() != buttons.isStop()) {
            // 按钮一直显示时（发送 ↔ 停止）图标淡出淡入；按钮刚出现时直接换图标（随按钮一起淡入）
            boolean animate = old != null && old.isActionVisible() && buttons.isActionVisible();
            setActionIcon(buttons.isStop(), animate);
        }
    }

    /**
     * 只做淡入淡出（Fade 改变 transitionAlpha，不影响变淡用的 alpha）；输入框 ↔「按住 说话」、麦克风 ↔ 键盘交叉淡入淡出。
     * 不用 ChangeBounds：它在播放期间暂停父布局的重新排版，切换输入方式时键盘正好弹出 / 收起，
     * 这次排版被跳过后输入框会停在过渡中途的宽度（变窄、提示文字折行）。
     */
    @NonNull
    private static Transition createTransition() {
        Fade fade = new Fade();
        fade.setDuration(TRANSITION_MS);
        fade.setInterpolator(new DecelerateInterpolator());
        return fade;
    }

    /**
     * 胶囊内按钮的对齐：输入框不可见（语音）或只有一行时整行垂直居中，文字与图标在同一水平线
     * （不依赖输入框正好 40dp 高：系统字体放大或字体本身较高时输入框会变高）；文字换行后贴底，按钮留在最后一行旁边。
     */
    void updatePillGravity() {
        EditText input = binding.aiComposerInput;
        boolean multiLine = input.getVisibility() == View.VISIBLE && input.getLineCount() > 1;
        int gravity = multiLine ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL;
        LinearLayout pill = binding.aiComposerPill;
        if (pill.getGravity() != gravity) {
            pill.setGravity(gravity);
        }
    }

    /**
     * 单行时按这台设备上中文字形的实际边界，把文字（含提示语）移到输入框正中：上下内边距一增一减、总高度不变。
     * 只按行框居中时，不同机型的系统字体上下留白不对称，文字会比两侧图标偏上或偏下（见 {@link GlyphCentering}）；
     * 多行时恢复布局文件中的内边距。
     */
    void centerInputText() {
        EditText input = binding.aiComposerInput;
        Layout layout = input.getLayout();
        if (released || layout == null || input.getVisibility() != View.VISIBLE) {
            return;
        }
        int shift = 0;
        if (input.getLineCount() <= 1) {
            input.getPaint().getTextBounds(GlyphCentering.SAMPLE, 0, GlyphCentering.SAMPLE.length(), glyphBounds);
            shift = GlyphCentering.shift(layout.getLineTop(0), layout.getLineBottom(0), layout.getLineBaseline(0),
                    glyphBounds.top, glyphBounds.bottom, Math.min(inputPaddingTop, inputPaddingBottom));
        }
        int top = inputPaddingTop + shift;
        int bottom = inputPaddingBottom - shift;
        if (input.getPaddingTop() != top || input.getPaddingBottom() != bottom) {
            input.setPadding(input.getPaddingLeft(), top, input.getPaddingRight(), bottom);
        }
    }

    private static void setShown(@NonNull View view, boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) {
            view.setVisibility(visibility);
        }
    }

    /** 右侧按钮：发送（arrow_upward）/ 停止（stop 实心，「停止生成」）；animate 时图标 150ms 淡出淡入 */
    private void setActionIcon(boolean stop, boolean animate) {
        ImageButton action = binding.aiComposerAction;
        int icon = stop ? com.heban.pestai.base.R.drawable.ic_stop_fill
                : com.heban.pestai.base.R.drawable.ic_arrow_upward;
        action.setContentDescription(action.getContext().getString(stop ? R.string.ai_stop : R.string.ai_send));
        if (iconAnimator != null) {
            iconAnimator.cancel();
            iconAnimator = null;
        }
        if (!animate) {
            action.setImageResource(icon);
            action.setImageAlpha(255);
            return;
        }
        ValueAnimator fade = ValueAnimator.ofInt(255, 0, 255);
        fade.setDuration(TRANSITION_MS);
        final boolean[] swapped = {false};
        fade.addUpdateListener(a -> {
            if (!swapped[0] && a.getAnimatedFraction() >= 0.5f) {
                swapped[0] = true;
                action.setImageResource(icon);
            }
            action.setImageAlpha((int) a.getAnimatedValue());
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                action.setImageResource(icon);
                action.setImageAlpha(255);
            }
        });
        iconAnimator = fade;
        fade.start();
    }

    // ==================== 键盘 ====================

    /** 用窗口的 WindowInsetsController 弹出键盘（比 showSoftInput 可靠）；输入框已失去焦点时不弹出 */
    private void showKeyboard() {
        EditText input = binding.aiComposerInput;
        if (released || !input.hasFocus()) {
            return;
        }
        Window window = findWindow(input.getContext());
        if (window != null) {
            WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime());
        } else {
            KeyboardUtils.show(input);
        }
    }

    private void removeFocusWaiter() {
        if (focusWaiter != null) {
            ViewTreeObserver observer = binding.aiComposerInput.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnWindowFocusChangeListener(focusWaiter);
            }
        }
        focusWaiter = null;
    }

    @Nullable
    private static Window findWindow(@NonNull Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return ((Activity) current).getWindow();
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
