package com.heban.pestai.ai.voice;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.color.MaterialColors;
import com.heban.pestai.ai.R;
import com.heban.pestai.ai.databinding.ViewAiVoicePanelBinding;
import com.heban.pestai.base.util.DimenUtils;

/**
 * 「按住 说话」的录音浮层（view_ai_voice_panel.xml）：加到页面内容区（android.R.id.content）最上层，
 * 水平居中在输入栏上方 16dp，不拦截触摸（按住的手指仍由「按住 说话」按钮接收）。
 * <ul>
 *     <li>录音中：深色半透明卡片，音量条 + 计时 + 提示；上滑进入取消区时变为红色半透明；</li>
 *     <li>识别中：音量条换成转圈，隐藏计时，提示「识别中…」；</li>
 *     <li>输入栏不可见时（Q01 切换到其他 Tab）跟着隐藏；每次布局后按输入栏位置重新定位（键盘弹出、缩略图条出现）。</li>
 * </ul>
 * 出现 120ms 淡入 + 轻微放大，消失 100ms 淡出后移除。
 */
final class VoicePanel {

    private static final long SHOW_MS = 120;
    private static final long HIDE_MS = 100;
    private static final float SHOW_SCALE_FROM = 0.92f;
    /** 与输入栏的间距 */
    private static final int MARGIN_DP = 16;

    /** 定位参照：整个输入栏（缩略图条 + 胶囊） */
    private final View anchor;
    private final ViewAiVoicePanelBinding binding;
    private final int margin;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::syncWithAnchor;
    @Nullable
    private ViewGroup host;
    @Nullable
    private ViewTreeObserver observer;
    /** 当前是否为取消（红色）样式；null 表示还没有设置过 */
    @Nullable
    private Boolean cancelStyle;
    private boolean showing;

    VoicePanel(@NonNull View anchor) {
        this.anchor = anchor;
        this.binding = ViewAiVoicePanelBinding.inflate(LayoutInflater.from(anchor.getContext()));
        this.margin = DimenUtils.dp(anchor.getContext(), MARGIN_DP);
    }

    /** 录音中：计时、提示与样式（cancel 为红色「松开 取消」） */
    void showRecording(@NonNull String timer, @NonNull String hint, boolean cancel) {
        attach();
        applyStyle(cancel);
        setShown(binding.aiVoiceBars, true);
        setShown(binding.aiVoiceProgress, false);
        setShown(binding.aiVoiceTimer, true);
        setText(binding.aiVoiceTimer, timer);
        setText(binding.aiVoiceHint, hint);
    }

    /** 识别中：转圈 +「识别中…」 */
    void showRecognizing(@NonNull String hint) {
        attach();
        applyStyle(false);
        // 音量条只隐藏不移除，卡片宽度不变
        binding.aiVoiceBars.setVisibility(View.INVISIBLE);
        setShown(binding.aiVoiceProgress, true);
        setShown(binding.aiVoiceTimer, false);
        setText(binding.aiVoiceHint, hint);
    }

    /** 音量条追加最新音量 */
    void pushLevel(float level) {
        binding.aiVoiceBars.push(level);
    }

    /** 新的一次录音开始前清空音量条 */
    void reset() {
        binding.aiVoiceBars.reset();
    }

    /** 淡出后移除 */
    void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        View panel = binding.getRoot();
        panel.animate().cancel();
        panel.animate().alpha(0f).setDuration(HIDE_MS).withEndAction(this::detach).start();
    }

    /** 立即移除（输入栏从窗口移除、页面销毁） */
    void release() {
        showing = false;
        binding.getRoot().animate().cancel();
        detach();
    }

    // ==================== 添加与定位 ====================

    private void attach() {
        View panel = binding.getRoot();
        if (showing && panel.getParent() != null) {
            return;
        }
        showing = true;
        panel.animate().cancel();
        if (panel.getParent() == null) {
            ViewGroup target = findHost();
            if (target == null) {
                return;
            }
            host = target;
            panel.setAlpha(0f);
            panel.setScaleX(SHOW_SCALE_FROM);
            panel.setScaleY(SHOW_SCALE_FROM);
            target.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            observer = anchor.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(layoutListener);
        }
        // 位置在布局后由 syncWithAnchor 设置；正在淡出时从当前透明度淡入
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(SHOW_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void detach() {
        View panel = binding.getRoot();
        if (observer != null) {
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(layoutListener);
            }
            observer = null;
        }
        ViewParent parent = panel.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(panel);
        }
        host = null;
    }

    /** 页面内容区（FrameLayout）；找不到时用窗口根视图；输入栏不在窗口中时不显示 */
    @Nullable
    private ViewGroup findHost() {
        if (!anchor.isAttachedToWindow()) {
            return null;
        }
        View root = anchor.getRootView();
        View content = root.findViewById(android.R.id.content);
        if (content instanceof FrameLayout) {
            return (ViewGroup) content;
        }
        return root instanceof FrameLayout ? (ViewGroup) root : null;
    }

    /** 每次布局后：输入栏不可见时隐藏；否则水平居中在输入栏上方 16dp（不超出内容区顶部） */
    private void syncWithAnchor() {
        View panel = binding.getRoot();
        ViewGroup target = host;
        if (target == null || panel.getParent() != target) {
            return;
        }
        int visibility = anchor.isShown() ? View.VISIBLE : View.INVISIBLE;
        if (panel.getVisibility() != visibility) {
            panel.setVisibility(visibility);
        }
        int[] anchorPos = new int[2];
        int[] hostPos = new int[2];
        anchor.getLocationInWindow(anchorPos);
        target.getLocationInWindow(hostPos);
        float x = anchorPos[0] - hostPos[0] + (anchor.getWidth() - panel.getWidth()) / 2f;
        float y = anchorPos[1] - hostPos[1] - panel.getHeight() - margin;
        panel.setTranslationX(x);
        panel.setTranslationY(Math.max(0f, y));
    }

    // ==================== 样式 ====================

    /** 正常：inverseSurface 底、onSurfaceInverse 文字；取消：error 底、onError 文字 */
    private void applyStyle(boolean cancel) {
        if (cancelStyle != null && cancelStyle == cancel) {
            return;
        }
        cancelStyle = cancel;
        View panel = binding.getRoot();
        @ColorRes int background = cancel ? R.color.ai_voice_panel_cancel : R.color.ai_voice_panel;
        panel.setBackgroundTintList(AppCompatResources.getColorStateList(panel.getContext(), background));
        int foreground = MaterialColors.getColor(panel, cancel
                ? com.google.android.material.R.attr.colorOnError
                : com.google.android.material.R.attr.colorOnSurfaceInverse);
        binding.aiVoiceTimer.setTextColor(foreground);
        binding.aiVoiceHint.setTextColor(foreground);
        binding.aiVoiceBars.setBarColor(foreground);
        binding.aiVoiceProgress.setIndicatorColor(foreground);
    }

    private static void setShown(@NonNull View view, boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) {
            view.setVisibility(visibility);
        }
    }

    private static void setText(@NonNull TextView view, @NonNull CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }
}
