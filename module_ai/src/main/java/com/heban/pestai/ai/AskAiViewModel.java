package com.heban.pestai.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.ai.chat.ChatArgs;
import com.heban.pestai.ai.compose.ComposerButtons;
import com.heban.pestai.ai.compose.ImageComposer;
import com.heban.pestai.ai.compose.ImageQueue;
import com.heban.pestai.ai.compose.InputMode;
import com.heban.pestai.ai.compose.InputModeStore;
import com.heban.pestai.ai.compose.PendingImage;
import com.heban.pestai.ai.compose.VoiceComposer;
import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.ai.util.AiFormat;
import com.heban.pestai.ai.voice.VoiceRecognizer;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;

import java.io.File;
import java.util.List;

import javax.inject.Inject;

/**
 * Q01 新对话：输入栏（文字与待发送图片）、游客判断、右上角历史对话。
 * Q01 只负责「发起」：发送时发出 OPEN_CHAT 事件打开 Q02（带上文字与已上传的图片），对话逻辑只在 ChatViewModel 中。
 * <ul>
 *     <li>输入栏（{@link ImageComposer}）：相机直接拍照、「+」直接打开相册多选；选好的照片加入 {@link ImageQueue}
 *     （最多 9 张）并立即上传，输入框获得焦点；全部上传完成才能发送，Q02 直接使用上传好的地址；</li>
 *     <li>按钮显示规则见 {@link ComposerButtons}（文字输入：有文字只显示发送，没有文字时显示相机、麦克风与「+」，有图片再加发送；
 *     语音输入：相机、「按住 说话」、键盘与「+」，有图片或隐藏的草稿时再加发送）；</li>
 *     <li>输入方式（{@link InputMode}）：打开页面、发送后、回到页面（onResume）时为记住的方式；麦克风 / 键盘图标切换并记住；</li>
 *     <li>语音输入（{@link VoiceComposer}）：录音交给 {@link VoiceRecognizer} 上传识别，文字追加到输入框并临时切到文字
 *     （页面弹出键盘方便修改），不自动发送；</li>
 *     <li>游客：输入框、相机、麦克风 / 键盘、「按住 说话」、「+」、发送与历史对话都弹出「登录后才能使用」。</li>
 * </ul>
 */
public class AskAiViewModel extends BaseViewModel implements ImageComposer, VoiceComposer {

    /** 一次性事件 */
    public static final class Event {
        public static final int NEED_LOGIN = 1;
        public static final int OPEN_CHAT = 2;
        /** 打开系统照片选择器（最多可选张数由页面向 ViewModel 查询） */
        public static final int PICK_IMAGE = 3;
        /** 打开 Q03 历史对话 */
        public static final int OPEN_HISTORY = 4;
        /** 输入框获得焦点（光标在末尾）并弹出键盘 */
        public static final int FOCUS_INPUT = 5;
        /** 申请相机权限后打开系统相机 */
        public static final int TAKE_PHOTO = 6;

        public final int type;
        @Nullable
        public final ChatArgs args;

        Event(int type, @Nullable ChatArgs args) {
            this.type = type;
            this.args = args;
        }
    }

    private final AiRepository repository;

    private final MutableLiveData<String> inputText = new MutableLiveData<>("");
    /** 输入栏上方的待发送图片（选好即上传） */
    private final ImageQueue images;
    /** 语音识别（「按住 说话」录好的 WAV） */
    private final VoiceRecognizer voice;
    /** 输入方式（文字 / 语音） */
    private final InputMode inputMode;
    /** 输入栏按钮显示状态 */
    private final MediatorLiveData<ComposerButtons> composerButtons = new MediatorLiveData<>();
    private final MutableLiveData<Boolean> guest = new MutableLiveData<>();
    private final SingleLiveEvent<Event> event = new SingleLiveEvent<>();

    @Inject
    public AskAiViewModel(@NonNull AiRepository repository, @NonNull InputModeStore inputModeStore) {
        this.repository = repository;
        this.images = new ImageQueue(repository, this::showToast);
        this.voice = new VoiceRecognizer(repository, this::onRecognized, this::showToast);
        this.inputMode = new InputMode(inputModeStore, this::showToast);
        guest.setValue(repository.isGuest());
        updateButtons();
        composerButtons.addSource(inputText, v -> updateButtons());
        composerButtons.addSource(images.getItems(), v -> updateButtons());
        composerButtons.addSource(inputMode.getVoice(), v -> updateButtons());
    }

    // ==================== 对外数据 ====================

    @NonNull
    @Override
    public MutableLiveData<String> getInputText() {
        return inputText;
    }

    @NonNull
    @Override
    public LiveData<List<PendingImage>> getImages() {
        return images.getItems();
    }

    @NonNull
    @Override
    public LiveData<Integer> getImageCount() {
        return images.getCount();
    }

    @NonNull
    @Override
    public LiveData<ComposerButtons> getComposerButtons() {
        return composerButtons;
    }

    /** 照片选择器本次最多可选张数 */
    public int remainingImages() {
        return images.remaining();
    }

    public LiveData<Boolean> getGuest() {
        return guest;
    }

    public LiveData<Event> getEvent() {
        return event;
    }

    public boolean isGuest() {
        return Boolean.TRUE.equals(guest.getValue());
    }

    /**
     * Fragment onResume：登录 / 退出登录后回到本页，更新游客状态（输入框是否可获得焦点）；
     * 输入方式同步为记住的方式（可能在 Q02 中切换过；识别后临时的文字输入保持不变）。
     */
    public void onResume() {
        boolean nowGuest = repository.isGuest();
        if (nowGuest != isGuest()) {
            guest.setValue(nowGuest);
        }
        inputMode.syncRemembered();
    }

    // ==================== 输入与发送 ====================

    /** 游客点击输入框：不弹键盘，弹出登录对话框（登录用户的输入框自己获得焦点） */
    public void onInputClick() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
        }
    }

    /** 右侧发送按钮（Q01 没有停止状态） */
    @Override
    public void onActionClick() {
        send();
    }

    /**
     * 发送：打开 Q02 并带上文字与已上传的图片（本地 Uri + 地址）；语音输入时发送隐藏的草稿 + 图片。
     * 有图片还在上传、或有图片上传失败时不发送并提示；发送后清空输入框与图片队列，输入方式回到记住的方式。
     */
    public void send() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        String text = AiFormat.trim(inputText.getValue());
        if (text.isEmpty() && images.isEmpty()) {
            return;
        }
        if (images.isUploading()) {
            showToast(AiConstants.TOAST_IMAGES_UPLOADING);
            return;
        }
        if (images.hasFailed()) {
            showToast(AiConstants.TOAST_IMAGES_FAILED);
            return;
        }
        event.setValue(new Event(Event.OPEN_CHAT,
                ChatArgs.ofQuestion(text, images.uploadedLocalUris(), images.uploadedUrls())));
        inputText.setValue("");
        images.clear();
        inputMode.restore();
    }

    // ==================== 输入栏图片 ====================

    /** 相机按钮：页面申请相机权限后打开系统相机 */
    @Override
    public void onCameraClick() {
        if (checkCanAddImage()) {
            event.setValue(new Event(Event.TAKE_PHOTO, null));
        }
    }

    /** 「+」按钮：页面按 {@link #remainingImages()} 打开照片选择器 */
    @Override
    public void onAlbumClick() {
        if (checkCanAddImage()) {
            event.setValue(new Event(Event.PICK_IMAGE, null));
        }
    }

    /**
     * 拍照 / 相册选好照片：加入队列并立即上传（超出 9 张的部分丢弃并提示），输入框获得焦点
     * （提示文字变为「描述这张照片的问题…」/「描述这些照片的问题…」）。
     */
    public void onImagesPicked(@NonNull List<String> uris) {
        if (isGuest()) {
            return;
        }
        if (images.add(uris) > 0) {
            event.setValue(new Event(Event.FOCUS_INPUT, null));
        }
    }

    @Override
    public void removeImage(long id) {
        images.remove(id);
    }

    @Override
    public void retryImage(long id) {
        images.retry(id);
    }

    /** 游客弹出登录；已满 9 张提示「最多只能添加 9 张图片」 */
    private boolean checkCanAddImage() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return false;
        }
        if (images.isFull()) {
            showToast(AiConstants.TOAST_TOO_MANY_IMAGES);
            return false;
        }
        return true;
    }

    // ==================== 语音输入 ====================

    @NonNull
    @Override
    public LiveData<Boolean> getVoiceMode() {
        return inputMode.getVoice();
    }

    /** 点按麦克风 / 键盘图标：游客弹出登录；否则切换（文字 → 语音没有权限时返回 true，由页面申请） */
    @Override
    public boolean onToggleInputMode(boolean micGranted) {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return false;
        }
        return inputMode.toggle(micGranted);
    }

    @Override
    public void onMicPermissionResult(boolean granted) {
        inputMode.onPermissionResult(granted);
    }

    /** 按下「按住 说话」：游客弹出登录；识别中忽略 */
    @Override
    public boolean onVoicePress() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return false;
        }
        return !voice.isRecognizing();
    }

    @Override
    public void onVoiceTooShort() {
        showToast(AiConstants.TOAST_VOICE_TOO_SHORT);
    }

    @Override
    public void onVoiceUnavailable() {
        showToast(AiConstants.TOAST_MIC_UNAVAILABLE);
    }

    /** 录音完成：上传识别（成功见 {@link #onRecognized}）；游客（录音期间退出登录）删除录音并弹出登录 */
    @Override
    public void recognizeSpeech(@NonNull File wav) {
        if (isGuest()) {
            VoiceRecognizer.deleteQuietly(wav);
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        voice.recognize(wav);
    }

    @NonNull
    @Override
    public LiveData<Boolean> getVoiceRecognizing() {
        return voice.getRecognizing();
    }

    @NonNull
    @Override
    public LiveData<String> getVoiceText() {
        return voice.getText();
    }

    /**
     * 识别成功（{@link VoiceRecognizer} 回调，随后发出 {@link #getVoiceText()} 事件）：文字追加到输入框草稿之后，
     * 临时切到文字输入（不改变记住的方式），页面让输入框获得焦点并弹出键盘，方便修改后再发送。
     */
    private void onRecognized(@NonNull String text) {
        String draft = inputText.getValue();
        inputText.setValue(AiFormat.isBlank(draft) ? text : draft + text);
        inputMode.showTextTemporarily();
    }

    // ==================== 顶部栏 ====================

    /** 右上角「历史对话」 */
    public void onHistoryClick() {
        event.setValue(new Event(isGuest() ? Event.NEED_LOGIN : Event.OPEN_HISTORY, null));
    }

    /** 有文字或有图片即显示发送按钮（图片还在上传时点按会提示）；Q01 不会生成中、总是就绪 */
    private void updateButtons() {
        ComposerButtons value = ComposerButtons.of(inputText.getValue(), images.size(), false, true,
                inputMode.isVoice());
        if (!value.equals(composerButtons.getValue())) {
            composerButtons.setValue(value);
        }
    }

    @Override
    protected void onCleared() {
        images.dispose();
        voice.dispose();
        super.onCleared();
    }
}
