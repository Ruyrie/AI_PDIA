package com.heban.pestai.ai.compose;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.ai.util.AiFormat;

/**
 * 输入栏胶囊中各按钮的显示状态（不可变，纯 Java，便于单元测试），由 ViewModel 计算、{@link ComposerBar} 显示。
 * 两种输入方式（见 {@link InputMode}）：
 * <ul>
 *     <li>文字：相机 · 输入框 · 麦克风（切换到语音）·「+」· 发送（停止）；</li>
 *     <li>语音：只有「按住 说话」（占相机与输入框的位置，输入框隐藏、文字保留）与键盘（与麦克风同一位置，切换回文字）；
 *     生成中右侧另有停止按钮。</li>
 * </ul>
 * 规则：
 * <ul>
 *     <li>文字、输入框有文字（去掉空白后非空）：只显示发送按钮，相机、麦克风与「+」隐藏；</li>
 *     <li>文字、没有文字也没有待发送图片：显示相机、麦克风与「+」，发送按钮隐藏；没有文字、有图片：再加发送按钮；</li>
 *     <li>语音：相机、「+」与发送按钮都隐藏，只显示「按住 说话」与键盘图标；隐藏的草稿与待发送图片保留，
 *     识别后自动切回文字时再按文字规则显示发送按钮；</li>
 *     <li>Q02 生成中：右侧按钮一直显示，为停止按钮（与文字无关）；其余按钮仍按上面的规则
 *     （生成中也可以语音输入，识别结果只填入输入框）；</li>
 *     <li>已有 9 张图片：相机与「+」变淡（仍可点，点按提示「最多只能添加 9 张图片」）；</li>
 *     <li>右侧按钮可点：生成中（停止），或有文字 / 图片且页面就绪（Q02 打开历史对话加载中、加载失败时不能发送）。</li>
 * </ul>
 */
public final class ComposerButtons {

    /** 文字输入、空输入、没有图片、不在生成中（页面初始状态） */
    public static final ComposerButtons EMPTY = of("", 0, false, true);

    private final boolean voiceMode;
    private final boolean cameraVisible;
    private final boolean micVisible;
    private final boolean keyboardVisible;
    private final boolean albumVisible;
    private final boolean actionVisible;
    private final boolean stop;
    private final boolean actionEnabled;
    private final boolean addDimmed;

    private ComposerButtons(boolean voiceMode, boolean cameraVisible, boolean micVisible, boolean keyboardVisible,
                            boolean albumVisible, boolean actionVisible, boolean stop, boolean actionEnabled,
                            boolean addDimmed) {
        this.voiceMode = voiceMode;
        this.cameraVisible = cameraVisible;
        this.micVisible = micVisible;
        this.keyboardVisible = keyboardVisible;
        this.albumVisible = albumVisible;
        this.actionVisible = actionVisible;
        this.stop = stop;
        this.actionEnabled = actionEnabled;
        this.addDimmed = addDimmed;
    }

    /** 文字输入时的按钮状态，参数见 {@link #of(String, int, boolean, boolean, boolean)} */
    @NonNull
    public static ComposerButtons of(@Nullable String text, int imageCount, boolean streaming, boolean ready) {
        return of(text, imageCount, streaming, ready, false);
    }

    /**
     * @param text       输入框文字（语音输入时为隐藏的草稿）
     * @param imageCount 待发送图片张数（含上传中、上传失败的）
     * @param streaming  Q02 正在生成回答（Q01 总是 false）
     * @param ready      页面可以发送（Q02 打开历史对话加载中 / 加载失败时为 false；Q01 总是 true）
     * @param voiceMode  语音输入（「按住 说话」代替输入框，键盘图标代替麦克风）
     */
    @NonNull
    public static ComposerButtons of(@Nullable String text, int imageCount, boolean streaming, boolean ready,
                                     boolean voiceMode) {
        boolean hasText = !AiFormat.isBlank(text);
        boolean hasInput = hasText || imageCount > 0;
        // 文字：有文字时只显示发送；语音：只有「按住 说话」与键盘图标（生成中另有停止按钮）
        boolean side = !voiceMode && !hasText;
        return new ComposerButtons(
                voiceMode,
                side,
                side,
                voiceMode,
                side,
                voiceMode ? streaming : hasInput || streaming,
                streaming,
                streaming || (hasInput && ready),
                imageCount >= AiConstants.MAX_IMAGES);
    }

    /** 语音输入：显示「按住 说话」（输入框隐藏、文字保留）与键盘图标 */
    public boolean isVoiceMode() {
        return voiceMode;
    }

    /** 输入框（文字输入时显示） */
    public boolean isInputVisible() {
        return !voiceMode;
    }

    /** 「按住 说话」按钮（语音输入时显示，占输入框的位置） */
    public boolean isTalkVisible() {
        return voiceMode;
    }

    /** 相机按钮（直接拍照） */
    public boolean isCameraVisible() {
        return cameraVisible;
    }

    /** 麦克风图标（点按切换到语音）：只在文字输入且没有文字时显示，不随图片张数变淡 */
    public boolean isMicVisible() {
        return micVisible;
    }

    /** 键盘图标（与麦克风同一位置，点按切换回文字）：语音输入时一直显示 */
    public boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    /** 「+」按钮（直接打开相册多选） */
    public boolean isAlbumVisible() {
        return albumVisible;
    }

    /** 右侧圆形按钮：发送，生成中为停止 */
    public boolean isActionVisible() {
        return actionVisible;
    }

    /** 右侧按钮为停止按钮（ic_stop_fill，「停止生成」） */
    public boolean isStop() {
        return stop;
    }

    public boolean isActionEnabled() {
        return actionEnabled;
    }

    /** 已满 9 张：相机与「+」变淡 */
    public boolean isAddDimmed() {
        return addDimmed;
    }

    /** 点右侧按钮会发送（可点且不是停止按钮） */
    public boolean canSend() {
        return actionEnabled && !stop;
    }

    /** 与另一状态相比，有按钮出现或消失、或输入框与「按住 说话」互换（需要过渡动画） */
    public boolean visibilityDiffers(@NonNull ComposerButtons other) {
        return voiceMode != other.voiceMode
                || cameraVisible != other.cameraVisible
                || micVisible != other.micVisible
                || keyboardVisible != other.keyboardVisible
                || albumVisible != other.albumVisible
                || actionVisible != other.actionVisible;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ComposerButtons)) {
            return false;
        }
        ComposerButtons that = (ComposerButtons) o;
        return voiceMode == that.voiceMode
                && cameraVisible == that.cameraVisible
                && micVisible == that.micVisible
                && keyboardVisible == that.keyboardVisible
                && albumVisible == that.albumVisible
                && actionVisible == that.actionVisible
                && stop == that.stop
                && actionEnabled == that.actionEnabled
                && addDimmed == that.addDimmed;
    }

    @Override
    public int hashCode() {
        int result = voiceMode ? 1 : 0;
        result = 31 * result + (cameraVisible ? 1 : 0);
        result = 31 * result + (micVisible ? 1 : 0);
        result = 31 * result + (keyboardVisible ? 1 : 0);
        result = 31 * result + (albumVisible ? 1 : 0);
        result = 31 * result + (actionVisible ? 1 : 0);
        result = 31 * result + (stop ? 1 : 0);
        result = 31 * result + (actionEnabled ? 1 : 0);
        result = 31 * result + (addDimmed ? 1 : 0);
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        return "ComposerButtons{voice=" + voiceMode + ", camera=" + cameraVisible + ", mic=" + micVisible
                + ", keyboard=" + keyboardVisible + ", album=" + albumVisible + ", action=" + actionVisible
                + ", stop=" + stop + ", enabled=" + actionEnabled + ", dimmed=" + addDimmed + '}';
    }
}
