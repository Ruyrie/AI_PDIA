package com.heban.pestai.ai.compose;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.ai.util.AiConstants;

import java.util.function.Consumer;

/**
 * 输入栏当前的输入方式（文字输入框 / 语音「按住 说话」），AskAiViewModel、ChatViewModel 各持有一个
 * （放在 ViewModel 中，旋转屏幕保持当前方式）。界面按 {@link ComposerButtons} 显示，见 {@link ComposerBar}。
 * <ul>
 *     <li>打开页面时为记住的方式（{@link InputModeStore}，Q01 / Q02 共用）；</li>
 *     <li>点按麦克风 / 键盘图标（{@link #toggle}，同一位置来回切换）：切换并记住；文字 → 语音时没有麦克风权限，
 *     先由页面申请，授权后才切换（{@link #onPermissionResult}），拒绝时留在文字并提示；</li>
 *     <li>识别成功（{@link #showTextTemporarily()}）：临时切到文字，方便修改识别结果，不改变记住的方式；</li>
 *     <li>发送成功清空输入框后（{@link #restore()}）回到记住的方式：用语音的用户又回到「按住 说话」；</li>
 *     <li>Q01 回到页面时（{@link #syncRemembered()}）同步在 Q02 中的切换（识别后的临时文字除外）。</li>
 * </ul>
 * 只在主线程使用。
 */
public final class InputMode {

    private final InputModeStore store;
    /** 提示文案（ViewModel 的 showToast） */
    private final Consumer<String> toast;
    private final MutableLiveData<Boolean> voice;
    /** 点按麦克风图标时没有权限、页面正在申请：授权后切到语音 */
    private boolean switchOnGrant;
    /** 当前是识别成功后临时切到的文字（记住的仍是语音） */
    private boolean temporaryText;

    public InputMode(@NonNull InputModeStore store, @NonNull Consumer<String> toast) {
        this.store = store;
        this.toast = toast;
        this.voice = new MutableLiveData<>(store.isVoiceMode());
    }

    /** 当前输入方式：true 为语音（「按住 说话」），false 为文字 */
    @NonNull
    public LiveData<Boolean> getVoice() {
        return voice;
    }

    public boolean isVoice() {
        return Boolean.TRUE.equals(voice.getValue());
    }

    /**
     * 点按麦克风 / 键盘图标（调用方已拦截游客）：语音 → 文字直接切换；文字 → 语音有权限时切换，没有时等待授权。
     * 切换都会记住。
     *
     * @param micGranted 已有麦克风权限
     * @return 需要页面申请麦克风权限（结果交给 {@link #onPermissionResult}）
     */
    public boolean toggle(boolean micGranted) {
        if (isVoice()) {
            switchOnGrant = false;
            choose(false);
            return false;
        }
        if (!micGranted) {
            switchOnGrant = true;
            return true;
        }
        switchOnGrant = false;
        choose(true);
        return false;
    }

    /**
     * 麦克风权限申请结果。
     * <ul>
     *     <li>点按麦克风图标时申请的：授权后切到语音（并记住），拒绝时留在文字并提示需要权限；</li>
     *     <li>语音模式下按住「按住 说话」时申请的（如权限被撤销）：授权后提示「已开启麦克风，请按住说话」
     *     （本次没有录音），拒绝时提示需要权限，都留在语音。</li>
     * </ul>
     */
    public void onPermissionResult(boolean granted) {
        boolean switching = switchOnGrant && !isVoice();
        switchOnGrant = false;
        if (switching && granted) {
            choose(true);
        } else if (granted) {
            toast.accept(AiConstants.TOAST_MIC_GRANTED);
        } else {
            toast.accept(AiConstants.TOAST_MIC_DENIED);
        }
    }

    /** 识别成功（文字已追加到输入框）：临时切到文字，不改变记住的方式 */
    public void showTextTemporarily() {
        if (isVoice()) {
            temporaryText = true;
            set(false);
        }
    }

    /** 发送成功清空输入框后：回到记住的方式 */
    public void restore() {
        temporaryText = false;
        set(store.isVoiceMode());
    }

    /** Q01 回到页面（onResume）：同步记住的方式（可能在 Q02 中切换过）；识别后的临时文字保持不变 */
    public void syncRemembered() {
        if (!temporaryText) {
            set(store.isVoiceMode());
        }
    }

    /** 当前是识别成功后临时切到的文字 */
    public boolean isTemporaryText() {
        return temporaryText;
    }

    /** 用户主动切换：记住并显示 */
    private void choose(boolean voiceMode) {
        temporaryText = false;
        store.setVoiceMode(voiceMode);
        set(voiceMode);
    }

    private void set(boolean voiceMode) {
        if (isVoice() != voiceMode) {
            voice.setValue(voiceMode);
        }
    }
}
