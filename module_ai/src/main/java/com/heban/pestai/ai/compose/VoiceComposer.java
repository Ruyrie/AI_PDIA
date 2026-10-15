package com.heban.pestai.ai.compose;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import java.io.File;

/**
 * Q01 / Q02 输入栏语音输入的 ViewModel 接口，由 AskAiViewModel、ChatViewModel 实现：
 * 文字 / 语音两种输入方式的切换（状态委托给 {@link InputMode}）与「按住 说话」的识别（委托给
 * {@link com.heban.pestai.ai.voice.VoiceRecognizer}）。界面部分见 {@link ComposerBar} 与
 * {@link com.heban.pestai.ai.voice.VoiceInputController}。
 * 输入方式与识别的订阅都保存在 ViewModel 中：旋转屏幕保持当前方式，识别中旋转屏幕结果仍会填入新页面的输入框。
 */
public interface VoiceComposer {

    // ==================== 输入方式 ====================

    /** 当前输入方式：true 为语音（「按住 说话」代替输入框），false 为文字 */
    @NonNull
    LiveData<Boolean> getVoiceMode();

    /**
     * 点按麦克风 / 键盘图标（胶囊中同一位置，来回切换）：游客发出登录事件（弹出「登录后才能使用」）；
     * 语音 → 文字直接切换（页面随后让输入框获得焦点并弹出键盘）；文字 → 语音有麦克风权限时切换（页面收起键盘），
     * 没有权限时返回 true。切换都会记住（下次打开页面、发送后使用）。
     *
     * @param micGranted 已有麦克风权限
     * @return 需要页面申请麦克风权限，结果交给 {@link #onMicPermissionResult(boolean)}
     */
    boolean onToggleInputMode(boolean micGranted);

    /**
     * 麦克风权限申请结果：点按麦克风图标申请的，授权后切到语音、拒绝时留在文字并提示；
     * 按住「按住 说话」时申请的，授权后提示「已开启麦克风，请按住说话」、拒绝时提示需要权限。
     */
    void onMicPermissionResult(boolean granted);

    // ==================== 按住说话 ====================

    /**
     * 按下「按住 说话」（或无障碍点按开始）。
     *
     * @return true 可以开始录音；游客发出登录事件（弹出「登录后才能使用」）返回 false；识别中直接忽略返回 false
     */
    boolean onVoicePress();

    /** 录音不足 1 秒：录音已丢弃，提示「说话时间太短」 */
    void onVoiceTooShort();

    /** 无法录音（麦克风被占用、初始化失败）：提示 */
    void onVoiceUnavailable();

    /**
     * 录音完成：上传识别（POST ai/asr）。成功时文字追加到输入框（草稿之后）、临时切到文字输入
     * （不改变记住的方式，发送后恢复），再发出 {@link #getVoiceText()} 事件；没识别出文字或失败时提示，留在语音。
     * 无论结果如何（含游客、识别中被忽略、页面关闭取消）都会删除 wav 文件。
     */
    void recognizeSpeech(@NonNull File wav);

    /** 正在识别：录音浮层与「按住 说话」显示「识别中…」，期间忽略新的按下 */
    @NonNull
    LiveData<Boolean> getVoiceRecognizing();

    /** 识别出的文字（一次性事件，已追加到输入框）：页面让输入框获得焦点、光标移到末尾并弹出键盘，不自动发送 */
    @NonNull
    LiveData<String> getVoiceText();
}
