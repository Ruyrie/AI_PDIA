package com.heban.pestai.ai.chat;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.ai.compose.ComposerButtons;
import com.heban.pestai.ai.compose.ImageComposer;
import com.heban.pestai.ai.compose.ImageQueue;
import com.heban.pestai.ai.compose.InputMode;
import com.heban.pestai.ai.compose.InputModeStore;
import com.heban.pestai.ai.compose.PendingImage;
import com.heban.pestai.ai.compose.VoiceComposer;
import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.data.StreamChunk;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.ai.util.AiFormat;
import com.heban.pestai.ai.util.ThinkingFormatter;
import com.heban.pestai.ai.voice.VoiceRecognizer;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.ChatRole;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.vo.ChatMessageVO;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.network.ApiException;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.LongSupplier;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Q02 对话页：会话、引用记录、消息列表、输入栏图片队列、流式生成（思考片段 + 正文片段）、停止 / 重新生成 / 重试、打开历史对话。
 * <ul>
 *     <li>会话在第一次发送时才创建（POST ai/sessions），之后的追问、重新生成、重试复用同一个 session_id；
 *     从 Q03 打开历史对话时（session_id）先 GET ai/sessions/{id} 渲染全部消息，继续提问直接追加到该会话；</li>
 *     <li>图片在输入栏中选好即上传（{@link ImageQueue}），全部上传完成才能发送：发送时只带 image_urls，
 *     列表中的图片没有上传状态；Q01 带过来的图片已上传，自动发送时不再上传；</li>
 *     <li>输入栏（{@link ImageComposer}）：相机直接拍照、「+」直接打开相册多选；按钮显示规则见 {@link ComposerButtons}
 *     （生成中右侧为停止按钮，与输入框文字无关）；</li>
 *     <li>输入方式（{@link InputMode}）：打开页面、发送后为记住的方式（与 Q01 共用）；麦克风 / 键盘图标切换并记住；</li>
 *     <li>语音输入（{@link VoiceComposer}）：录音交给 {@link VoiceRecognizer} 上传识别，文字追加到输入框并临时切到文字
 *     （页面弹出键盘方便修改）；生成中也可以使用（只填文字，不发送）；</li>
 *     <li>每收到一个片段只替换最后一条 AI 消息（ChatMessage 不可变）并更新 {@link TypingTracker} 的已收到字数，
 *     界面按帧逐字显示（ChatActivity 驱动，ChatAdapter 只局部刷新当前气泡）；</li>
 *     <li>流式订阅保存在 ViewModel 中：旋转屏幕回答继续输出，init() 只执行一次不会重复发送；
 *     停止生成 dispose 订阅即取消 OkHttp 请求，页面销毁时 onCleared 统一释放（含正在上传的图片）。</li>
 * </ul>
 */
public class ChatViewModel extends BaseViewModel implements ImageComposer, VoiceComposer {

    private final AiRepository repository;

    private final MutableLiveData<List<ChatMessage>> messages = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> inputText = new MutableLiveData<>("");
    /** 输入栏上方的待发送图片（选好即上传） */
    private final ImageQueue images;
    /** 语音识别（「按住 说话」录好的 WAV） */
    private final VoiceRecognizer voice;
    /** 输入方式（文字 / 语音） */
    private final InputMode inputMode;
    private final MutableLiveData<Boolean> streaming = new MutableLiveData<>(false);
    /** 输入栏按钮显示状态：生成中右侧为停止按钮；历史对话加载中 / 加载失败时不能发送 */
    private final MediatorLiveData<ComposerButtons> composerButtons = new MediatorLiveData<>();
    private final SingleLiveEvent<ChatEvent> event = new SingleLiveEvent<>();
    /** 打开历史对话的加载状态：LOADING / ERROR 时显示加载中 / 加载失败，不能发送；其余为 SUCCESS */
    private final MutableLiveData<UiState> loadState = new MutableLiveData<>(UiState.SUCCESS);
    private final MutableLiveData<String> loadError = new MutableLiveData<>("");
    /** 逐字显示进度（界面状态，放在 ViewModel 中使旋转屏幕后继续） */
    private final TypingTracker typing = new TypingTracker();

    /** io 线程写入、主线程读取 */
    @Nullable
    private volatile SessionVO session;
    private long recordId;
    /** 打开的历史对话 ID */
    private long historySessionId;
    private boolean initialized;
    private boolean guest;
    @Nullable
    private Question lastQuestion;
    @Nullable
    private Disposable streamTask;
    @Nullable
    private Disposable loadTask;
    /** 发送时刻 / 思考耗时的时钟（单元测试可替换） */
    private LongSupplier clock = SystemClock::elapsedRealtime;

    @Inject
    public ChatViewModel(@NonNull AiRepository repository, @NonNull InputModeStore inputModeStore) {
        this.repository = repository;
        this.images = new ImageQueue(repository, this::showToast);
        this.voice = new VoiceRecognizer(repository, this::onRecognized, this::showToast);
        this.inputMode = new InputMode(inputModeStore, this::showToast);
        updateButtons();
        composerButtons.addSource(inputText, v -> updateButtons());
        composerButtons.addSource(images.getItems(), v -> updateButtons());
        composerButtons.addSource(streaming, v -> updateButtons());
        composerButtons.addSource(loadState, v -> updateButtons());
        composerButtons.addSource(inputMode.getVoice(), v -> updateButtons());
    }

    // ==================== 对外数据 ====================

    public LiveData<List<ChatMessage>> getMessages() {
        return messages;
    }

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

    public LiveData<ChatEvent> getEvent() {
        return event;
    }

    public LiveData<UiState> getLoadState() {
        return loadState;
    }

    public LiveData<String> getLoadError() {
        return loadError;
    }

    /** 逐字显示进度，由 ChatActivity 逐帧推进 */
    @NonNull
    public TypingTracker getTyping() {
        return typing;
    }

    public boolean isStreaming() {
        return Boolean.TRUE.equals(streaming.getValue());
    }

    /** 历史对话加载中或加载失败：不能发送 */
    private boolean isLoading() {
        UiState state = loadState.getValue();
        return state == UiState.LOADING || state == UiState.ERROR;
    }

    // ==================== 页面操作 ====================

    /**
     * 只在第一次调用时生效（旋转屏幕重建页面不会重复加载、重复发送）。
     * 带问题或图片时自动发送；图片已由 Q01 上传，直接使用其地址。
     */
    public void init(@NonNull ChatArgs args) {
        if (initialized) {
            return;
        }
        initialized = true;
        guest = repository.isGuest();
        if (args.hasSession() && !guest) {
            historySessionId = args.sessionId;
            loadSession();
            return;
        }
        recordId = Math.max(args.recordId, 0);
        if (recordId > 0 && !guest) {
            loadQuote(recordId);
        }
        String question = AiFormat.trim(args.question);
        if (question.isEmpty() && !args.hasImages()) {
            return;
        }
        if (guest) {
            inputText.setValue(question);
            event.setValue(ChatEvent.of(ChatEvent.NEED_LOGIN));
            return;
        }
        ask(question, args.imageUris, args.imageUrls);
    }

    /** 右侧按钮：生成中为「停止生成」，否则为「发送」 */
    @Override
    public void onActionClick() {
        if (isStreaming()) {
            stopGenerating();
        } else {
            send();
        }
    }

    /**
     * 发送输入框文字（语音输入时为隐藏的草稿）与已上传的图片：有图片还在上传、或有图片上传失败时不发送并提示；
     * 只发图片时 content 为空串。发送后清空输入框与图片队列，输入方式回到记住的方式。
     */
    public void send() {
        if (isStreaming() || isLoading()) {
            return;
        }
        if (guest) {
            event.setValue(ChatEvent.of(ChatEvent.NEED_LOGIN));
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
        List<String> localUris = images.uploadedLocalUris();
        List<String> urls = images.uploadedUrls();
        inputText.setValue("");
        images.clear();
        inputMode.restore();
        ask(text, localUris, urls);
    }

    /** 列表中加入图片项（有图片时）、文字气泡（有文字时）与等待回答的 AI 气泡，然后请求回答 */
    private void ask(@NonNull String text, @NonNull List<String> localUris, @NonNull List<String> imageUrls) {
        String content = text;
        if (content.length() > AiConstants.MAX_QUESTION_LENGTH) {
            content = AiFormat.truncate(content, AiConstants.MAX_QUESTION_LENGTH);
            showToast(AiConstants.TOAST_QUESTION_TOO_LONG);
        }
        List<ChatMessage> list = copy();
        long imageId = 0;
        long textId = 0;
        if (!imageUrls.isEmpty()) {
            ChatMessage imageMessage = ChatMessage.userImages(localUris, imageUrls, new Date());
            imageId = imageMessage.getLocalId();
            list.add(imageMessage);
        }
        if (!content.isEmpty()) {
            ChatMessage textMessage = ChatMessage.userText(content);
            textId = textMessage.getLocalId();
            list.add(textMessage);
        }
        ChatMessage pending = ChatMessage.aiPending(clock.getAsLong());
        typing.begin(pending.getLocalId());
        list.add(pending);
        lastQuestion = new Question(content, content, imageUrls, localUris, imageId, textId);

        commit(list);
        event.setValue(ChatEvent.of(ChatEvent.SCROLL_TO_BOTTOM));
        startAnswer(false);
    }

    /**
     * 停止生成：取消订阅（OkHttp 请求随之取消），保留已输出的文字与思考内容（思考区折叠：正文未开始为「已停止思考」）；
     * 既没有正文也没有思考内容时移除空气泡。
     */
    public void stopGenerating() {
        Disposable task = streamTask;
        if (task == null) {
            return;
        }
        streamTask = null;
        disposables.remove(task);
        List<ChatMessage> list = copy();
        int last = list.size() - 1;
        if (last >= 0 && list.get(last).getType() == ChatMessage.TYPE_AI) {
            ChatMessage ai = list.get(last);
            if (ai.hasContent() || ai.hasReasoning()) {
                list.set(last, ai.finish(ChatMessage.NOTICE_NONE));
                typing.finish(ai.getLocalId());
            } else {
                list.remove(last);
                typing.remove(ai.getLocalId());
            }
        }
        streaming.setValue(false);
        commit(list);
    }

    /**
     * 重新生成：删除最后一条 AI 回答，用上一个问题（同样的 content 与 image_urls）重新请求（regenerate 为 true）；
     * 生成中不响应。
     */
    public void regenerate() {
        if (isStreaming() || lastQuestion == null) {
            return;
        }
        List<ChatMessage> list = copy();
        int last = list.size() - 1;
        if (last < 0 || list.get(last).getType() != ChatMessage.TYPE_AI) {
            return;
        }
        typing.remove(list.remove(last).getLocalId());
        restartAnswer(list);
    }

    /** 回答中断 / AI 服务不可用后点按重试，逻辑同重新生成 */
    public void retry() {
        if (isStreaming() || lastQuestion == null) {
            return;
        }
        List<ChatMessage> list = copy();
        int last = list.size() - 1;
        if (last >= 0 && list.get(last).getType() == ChatMessage.TYPE_AI) {
            typing.remove(list.remove(last).getLocalId());
        }
        restartAnswer(list);
    }

    public void openQuotedRecord(@NonNull ChatMessage message) {
        RecordItemVO quote = message.getQuote();
        if (quote != null) {
            event.setValue(ChatEvent.openRecord(quote.getId()));
        }
    }

    // ==================== 输入栏图片 ====================

    /** 相机按钮：页面申请相机权限后打开系统相机；已满 9 张时提示 */
    @Override
    public void onCameraClick() {
        if (checkCanAddImage()) {
            event.setValue(ChatEvent.of(ChatEvent.TAKE_PHOTO));
        }
    }

    /** 「+」按钮：页面按 {@link #remainingImages()} 打开照片选择器；已满 9 张时提示 */
    @Override
    public void onAlbumClick() {
        if (checkCanAddImage()) {
            event.setValue(ChatEvent.of(ChatEvent.PICK_IMAGE));
        }
    }

    /** 拍照 / 相册选好：加入队列并立即上传（超出 9 张的部分丢弃并提示） */
    public void onImagesPicked(@NonNull List<String> uris) {
        if (!guest) {
            images.add(uris);
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
        if (guest) {
            event.setValue(ChatEvent.of(ChatEvent.NEED_LOGIN));
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
        if (guest) {
            event.setValue(ChatEvent.of(ChatEvent.NEED_LOGIN));
            return false;
        }
        return inputMode.toggle(micGranted);
    }

    @Override
    public void onMicPermissionResult(boolean granted) {
        inputMode.onPermissionResult(granted);
    }

    /** 按下「按住 说话」：游客弹出登录；识别中忽略（生成中、历史对话加载中都可以录音，只填文字） */
    @Override
    public boolean onVoicePress() {
        if (guest) {
            event.setValue(ChatEvent.of(ChatEvent.NEED_LOGIN));
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

    /** 录音完成：上传识别（成功见 {@link #onRecognized}）；游客删除录音并弹出登录 */
    @Override
    public void recognizeSpeech(@NonNull File wav) {
        if (guest) {
            VoiceRecognizer.deleteQuietly(wav);
            event.setValue(ChatEvent.of(ChatEvent.NEED_LOGIN));
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

    /** 点击折叠后的思考区标题：展开 / 收起思考内容（思考中不响应） */
    public void toggleReasoning(@NonNull ChatMessage message) {
        List<ChatMessage> list = copy();
        for (int i = 0; i < list.size(); i++) {
            ChatMessage m = list.get(i);
            if (m.getLocalId() == message.getLocalId()) {
                if (ThinkingFormatter.isCollapsible(m.thinkingState())) {
                    list.set(i, m.withReasoningExpanded(!m.isReasoningExpanded()));
                    messages.setValue(Collections.unmodifiableList(list));
                }
                return;
            }
        }
    }

    // ==================== 历史对话 ====================

    /** 加载失败后点「重试」 */
    public void retryLoad() {
        if (historySessionId > 0 && loadState.getValue() == UiState.ERROR) {
            loadSession();
        }
    }

    private void loadSession() {
        if (loadTask != null) {
            disposables.remove(loadTask);
            loadTask = null;
        }
        loadState.setValue(UiState.LOADING);
        loadError.setValue("");
        Disposable task = repository.session(historySessionId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showSession, error -> {
                    loadTask = null;
                    loadError.setValue(ApiException.messageOf(error));
                    loadState.setValue(UiState.ERROR);
                });
        if (!task.isDisposed()) {
            loadTask = task;
            add(task);
        }
    }

    private void showSession(@NonNull SessionDetailVO detail) {
        loadTask = null;
        SessionVO vo = new SessionVO();
        vo.setSessionId(detail.getSessionId() > 0 ? detail.getSessionId() : historySessionId);
        vo.setRecordId(detail.getRecordId());
        vo.setTitle(detail.getTitle());
        session = vo;
        recordId = detail.getRecordId() == null ? 0 : Math.max(0, detail.getRecordId());

        History history = buildHistory(detail);
        lastQuestion = history.lastQuestion;
        // 加载期间不能发送，直接替换列表
        commit(new ArrayList<>(history.messages));
        loadState.setValue(UiState.SUCCESS);
        event.setValue(ChatEvent.of(ChatEvent.SCROLL_TO_BOTTOM));
    }

    /**
     * 把 SessionDetailVO 转换为列表项：关联记录存在时第一项为引用卡；用户消息带图时先图片（一个九宫格项）后文字；
     * 图片取 image_urls，为空时退回旧字段 image_url；只发图片时没有文字气泡（新数据 content 为空串，
     * 旧数据为 {@link AiConstants#DEFAULT_IMAGE_QUESTION}）；AI 回答保留思考内容与耗时。
     * 同时取最后一条用户消息作为「重新生成」使用的问题（原样重发 content 与 image_urls，图片已在服务器上）。
     */
    @NonNull
    static History buildHistory(@NonNull SessionDetailVO detail) {
        List<ChatMessage> list = new ArrayList<>();
        Question question = null;
        RecordItemVO record = detail.getRecord();
        if (record != null) {
            list.add(ChatMessage.quote(record));
        }
        List<ChatMessageVO> vos = detail.getMessages();
        if (vos != null) {
            for (ChatMessageVO vo : vos) {
                if (vo == null) {
                    continue;
                }
                if (ChatRole.USER.equals(vo.getRole())) {
                    List<String> urls = imageUrlsOf(vo);
                    String content = AiFormat.trim(vo.getContent());
                    long imageId = 0;
                    long textId = 0;
                    if (!urls.isEmpty()) {
                        ChatMessage m = ChatMessage.userImages(Collections.emptyList(), urls, vo.getCreatedAt());
                        imageId = m.getLocalId();
                        list.add(m);
                    }
                    boolean defaultQuestion = !urls.isEmpty() && AiConstants.DEFAULT_IMAGE_QUESTION.equals(content);
                    String text = defaultQuestion ? "" : content;
                    if (!text.isEmpty()) {
                        ChatMessage m = ChatMessage.userText(text, vo.getCreatedAt());
                        textId = m.getLocalId();
                        list.add(m);
                    }
                    if (!content.isEmpty() || !urls.isEmpty()) {
                        question = new Question(text, content, urls, Collections.emptyList(), imageId, textId);
                    }
                } else if (ChatRole.ASSISTANT.equals(vo.getRole())) {
                    ChatMessage ai = ChatMessage.aiHistory(vo.getContent(), vo.getReasoningContent(),
                            vo.getReasoningMs(), vo.getCreatedAt());
                    // 正文为空但有思考内容（正文开始前停止生成）：只显示折叠的「已停止思考」，不显示提示；
                    // 两者都为空才提示「AI 暂时没有给出回答」
                    if (!ai.hasContent() && !ai.hasReasoning()) {
                        ai = ai.finish(ChatMessage.NOTICE_EMPTY);
                    }
                    list.add(ai);
                }
            }
        }
        return new History(list, question);
    }

    /** 用户消息的图片地址：优先 image_urls（去掉空值），为空时退回旧字段 image_url */
    @NonNull
    static List<String> imageUrlsOf(@NonNull ChatMessageVO vo) {
        List<String> result = new ArrayList<>();
        List<String> urls = vo.getImageUrls();
        if (urls != null) {
            for (String url : urls) {
                if (!AiFormat.isBlank(url)) {
                    result.add(url.trim());
                }
            }
        }
        if (result.isEmpty() && !AiFormat.isBlank(vo.getImageUrl())) {
            result.add(vo.getImageUrl().trim());
        }
        return Collections.unmodifiableList(result);
    }

    // ==================== 引用卡 ====================

    private void loadQuote(long id) {
        add(repository.record(id)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                // 记录已删除等失败情况：不显示引用卡，对话照常进行
                .subscribe(this::showQuote, error -> { }));
    }

    private void showQuote(@NonNull RecordItemVO record) {
        String status = record.getStatus();
        if (status != null && !RecognitionStatus.SUCCESS.equals(status)) {
            return;
        }
        List<ChatMessage> list = copy();
        if (!list.isEmpty() && list.get(0).getType() == ChatMessage.TYPE_QUOTE) {
            return;
        }
        list.add(0, ChatMessage.quote(record));
        commit(list);
    }

    // ==================== 生成回答 ====================

    private void restartAnswer(@NonNull List<ChatMessage> list) {
        ChatMessage pending = ChatMessage.aiPending(clock.getAsLong());
        typing.begin(pending.getLocalId());
        list.add(pending);
        commit(list);
        event.setValue(ChatEvent.of(ChatEvent.SCROLL_TO_BOTTOM));
        startAnswer(true);
    }

    private void startAnswer(boolean regenerate) {
        final Question q = lastQuestion;
        if (q == null) {
            return;
        }
        streaming.setValue(true);
        Disposable task = ensureSession()
                .flatMapPublisher(sessionId -> repository
                        .sendMessage(sessionId, q.content, q.imageUrls, regenerate)
                        .subscribeOn(Schedulers.io()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::appendChunk, this::onStreamError, this::onStreamComplete);
        // 同步完成（单元测试中调度器为 trampoline）时订阅已结束，不再保存
        if (!task.isDisposed()) {
            streamTask = task;
            add(task);
        }
    }

    /** 已有会话直接复用；没有则创建（带 record_id 时后端附加识别结论） */
    @NonNull
    private Single<Long> ensureSession() {
        SessionVO current = session;
        if (current != null) {
            return Single.just(current.getSessionId());
        }
        Long rid = recordId > 0 ? recordId : null;
        return repository.createSession(rid)
                .subscribeOn(Schedulers.io())
                .doOnSuccess(vo -> session = vo)
                .map(SessionVO::getSessionId);
    }

    /**
     * 思考片段追加到 reasoning，正文片段追加到 content；收到第一个正文片段时记录思考耗时（有思考内容时）。
     * 同时更新逐字显示的已收到字数。
     */
    private void appendChunk(@NonNull StreamChunk chunk) {
        List<ChatMessage> list = copy();
        int last = list.size() - 1;
        if (last < 0) {
            return;
        }
        ChatMessage ai = list.get(last);
        if (ai.getType() != ChatMessage.TYPE_AI || !ai.isStreaming()) {
            return;
        }
        ChatMessage updated;
        if (chunk.isReasoning()) {
            updated = ai.withReasoning(ai.getReasoning() + chunk.text);
            typing.setReasoning(ai.getLocalId(), updated.getReasoning().length());
        } else {
            updated = ai.withContent(ai.getContent() + chunk.text);
            if (ai.getContent().isEmpty() && ai.hasReasoning() && ai.getReasoningMs() == null) {
                updated = updated.withReasoningMs(Math.max(0, clock.getAsLong() - ai.getStartedAt()));
            }
            typing.setContent(ai.getLocalId(), updated.getContent().length());
        }
        list.set(last, updated);
        messages.setValue(Collections.unmodifiableList(list));
    }

    private void onStreamComplete() {
        releaseStreamTask();
        streaming.setValue(false);
        List<ChatMessage> list = copy();
        int last = list.size() - 1;
        if (last >= 0 && list.get(last).getType() == ChatMessage.TYPE_AI) {
            ChatMessage ai = list.get(last);
            list.set(last, ai.finish(ai.hasContent() ? ChatMessage.NOTICE_NONE : ChatMessage.NOTICE_EMPTY));
            typing.finish(ai.getLocalId());
        }
        commit(list);
    }

    private void onStreamError(@NonNull Throwable error) {
        releaseStreamTask();
        streaming.setValue(false);
        List<ChatMessage> list = copy();
        int last = list.size() - 1;
        ChatMessage ai = last >= 0 && list.get(last).getType() == ChatMessage.TYPE_AI ? list.get(last) : null;
        int code = error instanceof ApiException ? ((ApiException) error).getCode() : ApiException.NETWORK;
        if (code == ApiException.AI_UNAVAILABLE) {
            if (ai != null) {
                list.set(last, ai.finish(ChatMessage.NOTICE_UNAVAILABLE));
                typing.finish(ai.getLocalId());
            }
            commit(list);
        } else if (isBusinessError(code)) {
            // 40101、42901、40001 等流开始前的错误：移除空气泡并提示（40101 的跳转登录由壳工程统一处理）
            if (ai != null) {
                if (ai.hasContent() || ai.hasReasoning()) {
                    list.set(last, ai.finish(ChatMessage.NOTICE_BROKEN));
                    typing.finish(ai.getLocalId());
                } else {
                    list.remove(last);
                    typing.remove(ai.getLocalId());
                }
            }
            commit(list);
            showToast(error.getMessage());
        } else {
            // 断网、超时、没收到 [DONE] 就断开
            if (ai != null) {
                list.set(last, ai.finish(ChatMessage.NOTICE_BROKEN));
                typing.finish(ai.getLocalId());
            }
            commit(list);
        }
    }

    private static boolean isBusinessError(int code) {
        return code > 0 && code != ApiException.AI_TIMEOUT;
    }

    private void releaseStreamTask() {
        Disposable task = streamTask;
        if (task != null) {
            disposables.delete(task);
            streamTask = null;
        }
    }

    // ==================== 列表工具 ====================

    @NonNull
    private List<ChatMessage> copy() {
        List<ChatMessage> current = messages.getValue();
        return current == null ? new ArrayList<>() : new ArrayList<>(current);
    }

    /** 提交新列表：只有列表最后一项是 AI 回答时它才是「最新回答」（显示重新生成） */
    private void commit(@NonNull List<ChatMessage> list) {
        int last = list.size() - 1;
        for (int i = 0; i <= last; i++) {
            ChatMessage m = list.get(i);
            if (m.getType() == ChatMessage.TYPE_AI) {
                boolean latest = i == last;
                if (m.isLatest() != latest) {
                    list.set(i, m.withLatest(latest));
                }
            }
        }
        messages.setValue(Collections.unmodifiableList(list));
    }

    /** 输入栏按钮：有文字或有图片即可发送（图片还在上传时点按会提示），生成中为停止按钮，规则见 {@link ComposerButtons} */
    private void updateButtons() {
        ComposerButtons value = ComposerButtons.of(inputText.getValue(), images.size(), isStreaming(), !isLoading(),
                inputMode.isVoice());
        if (!value.equals(composerButtons.getValue())) {
            composerButtons.setValue(value);
        }
    }

    @Override
    protected void onCleared() {
        typing.clear();
        images.dispose();
        voice.dispose();
        super.onCleared();
    }

    @VisibleForTesting
    void setClock(@NonNull LongSupplier clock) {
        this.clock = clock;
    }

    // ==================== 内部类型 ====================

    /** 最近一次提问：重新生成与重试时原样重发 */
    static final class Question {
        /** 列表中显示的文字（只发图片时为空串） */
        final String text;
        /** 实际发送的 content（旧数据只发图片时为默认问题，新数据为空串） */
        final String content;
        /** 已上传图片的地址（按顺序），没有图片为空列表 */
        @NonNull
        final List<String> imageUrls;
        /** 与 imageUrls 对应的本地 Uri（本次发送的图片，只用于显示），历史消息为空列表 */
        @NonNull
        final List<String> localUris;
        /** 本轮提问在列表中的图片项 / 文字项 ID（没有为 0） */
        final long imageMessageId;
        final long textMessageId;

        Question(@NonNull String text, @NonNull String content, @NonNull List<String> imageUrls,
                 @NonNull List<String> localUris, long imageMessageId, long textMessageId) {
            this.text = text;
            this.content = content;
            this.imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
            this.localUris = Collections.unmodifiableList(new ArrayList<>(localUris));
            this.imageMessageId = imageMessageId;
            this.textMessageId = textMessageId;
        }
    }

    /** 历史对话转换结果：列表项与最后一个问题 */
    static final class History {
        final List<ChatMessage> messages;
        @Nullable
        final Question lastQuestion;

        History(@NonNull List<ChatMessage> messages, @Nullable Question lastQuestion) {
            this.messages = messages;
            this.lastQuestion = lastQuestion;
        }
    }
}
