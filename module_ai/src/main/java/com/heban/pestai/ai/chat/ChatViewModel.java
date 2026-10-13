package com.heban.pestai.ai.chat;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.data.StreamChunk;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.ai.util.AiFormat;
import com.heban.pestai.ai.util.ThinkingFormatter;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.constant.ChatRole;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.vo.ChatMessageVO;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.network.ApiException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Q02 对话页：会话、引用记录、消息列表、流式生成（思考片段 + 正文片段）、停止 / 重新生成 / 重试、打开历史对话。
 * <ul>
 *     <li>会话在第一次发送时才创建（POST ai/sessions），之后的追问、重新生成、重试复用同一个 session_id；
 *     从 Q03 打开历史对话时（session_id）先 GET ai/sessions/{id} 渲染全部消息，继续提问直接追加到该会话；</li>
 *     <li>带图时「创建会话」与「压缩上传图片」用 Single.zip 并行，都完成后 flatMapPublisher 接上流式请求；</li>
 *     <li>每收到一个片段只替换最后一条 AI 消息（ChatMessage 不可变）并更新 {@link TypingTracker} 的已收到字数，
 *     界面按帧逐字显示（ChatActivity 驱动，ChatAdapter 只局部刷新当前气泡）；</li>
 *     <li>流式订阅保存在 ViewModel 中：旋转屏幕回答继续输出，init() 只执行一次不会重复发送；
 *     停止生成 dispose 订阅即取消 OkHttp 请求，页面销毁时 onCleared 统一释放。</li>
 * </ul>
 */
public class ChatViewModel extends BaseViewModel {

    private final AiRepository repository;

    private final MutableLiveData<List<ChatMessage>> messages = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> inputText = new MutableLiveData<>("");
    /** 待发送图片的本地 Uri 字符串 */
    private final MutableLiveData<String> pendingImage = new MutableLiveData<>();
    private final MutableLiveData<Boolean> streaming = new MutableLiveData<>(false);
    /** 文字非空或有图片，且不在生成中 */
    private final MediatorLiveData<Boolean> canSend = new MediatorLiveData<>();
    /** 右侧按钮是否可点：可发送，或生成中（此时为停止按钮） */
    private final MediatorLiveData<Boolean> actionEnabled = new MediatorLiveData<>();
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
    /** 图片读取失败撤回本次提问时，恢复上一轮问题（供重新生成使用） */
    @Nullable
    private Question previousQuestion;
    @Nullable
    private Disposable streamTask;
    @Nullable
    private Disposable loadTask;
    /** 发送时刻 / 思考耗时的时钟（单元测试可替换） */
    private LongSupplier clock = SystemClock::elapsedRealtime;

    @Inject
    public ChatViewModel(@NonNull AiRepository repository) {
        this.repository = repository;
        canSend.setValue(false);
        actionEnabled.setValue(false);
        canSend.addSource(inputText, v -> updateSendState());
        canSend.addSource(pendingImage, v -> updateSendState());
        canSend.addSource(streaming, v -> updateSendState());
        canSend.addSource(loadState, v -> updateSendState());
        actionEnabled.addSource(inputText, v -> updateSendState());
        actionEnabled.addSource(pendingImage, v -> updateSendState());
        actionEnabled.addSource(streaming, v -> updateSendState());
        actionEnabled.addSource(loadState, v -> updateSendState());
    }

    // ==================== 对外数据 ====================

    public LiveData<List<ChatMessage>> getMessages() {
        return messages;
    }

    public MutableLiveData<String> getInputText() {
        return inputText;
    }

    public LiveData<String> getPendingImage() {
        return pendingImage;
    }

    public LiveData<Boolean> getStreaming() {
        return streaming;
    }

    public LiveData<Boolean> getCanSend() {
        return canSend;
    }

    public LiveData<Boolean> getActionEnabled() {
        return actionEnabled;
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

    /** 只在第一次调用时生效（旋转屏幕重建页面不会重复加载、重复发送） */
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
        boolean hasQuestion = !AiFormat.isBlank(args.question);
        if (hasQuestion) {
            inputText.setValue(args.question);
        }
        if (args.imageUri != null) {
            pendingImage.setValue(args.imageUri);
        }
        if (hasQuestion || args.imageUri != null) {
            send();
        }
    }

    /** 右侧按钮：生成中为「停止生成」，否则为「发送」 */
    public void onSendOrStop() {
        if (isStreaming()) {
            stopGenerating();
        } else {
            send();
        }
    }

    public void send() {
        if (isStreaming() || isLoading()) {
            return;
        }
        if (guest) {
            event.setValue(ChatEvent.of(ChatEvent.NEED_LOGIN));
            return;
        }
        String text = AiFormat.trim(inputText.getValue());
        String image = pendingImage.getValue();
        if (text.isEmpty() && image == null) {
            return;
        }
        if (text.length() > AiConstants.MAX_QUESTION_LENGTH) {
            text = AiFormat.truncate(text, AiConstants.MAX_QUESTION_LENGTH);
            showToast(AiConstants.TOAST_QUESTION_TOO_LONG);
        }
        String content = text.isEmpty() ? AiConstants.DEFAULT_IMAGE_QUESTION : text;

        List<ChatMessage> list = copy();
        long imageId = 0;
        long textId = 0;
        if (image != null) {
            ChatMessage imageMessage = ChatMessage.userImage(image);
            imageId = imageMessage.getLocalId();
            list.add(imageMessage);
        }
        if (!text.isEmpty()) {
            ChatMessage textMessage = ChatMessage.userText(text);
            textId = textMessage.getLocalId();
            list.add(textMessage);
        }
        ChatMessage pending = ChatMessage.aiPending(clock.getAsLong());
        typing.begin(pending.getLocalId());
        list.add(pending);
        previousQuestion = lastQuestion;
        lastQuestion = new Question(text, content, image, imageId, textId);

        inputText.setValue("");
        pendingImage.setValue(null);
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
        resetUploadingImages(list);
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

    /** 重新生成：删除最后一条 AI 回答，用上一个问题重新请求（regenerate 为 true）；生成中不响应 */
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

    /** 回答中断 / AI 服务不可用 / 图片上传失败后点按重试，逻辑同重新生成 */
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

    /** 点击用户图片：上传失败的是最近一次提问的图片时重新上传并提问 */
    public void onImageClick(@NonNull ChatMessage message) {
        Question q = lastQuestion;
        if (message.getImageState() == ChatMessage.IMAGE_FAILED && q != null
                && q.imageMessageId == message.getLocalId()) {
            retry();
        }
    }

    public void openQuotedRecord(@NonNull ChatMessage message) {
        RecordItemVO quote = message.getQuote();
        if (quote != null) {
            event.setValue(ChatEvent.openRecord(quote.getId()));
        }
    }

    public void onPickImageClick() {
        event.setValue(ChatEvent.of(guest ? ChatEvent.NEED_LOGIN : ChatEvent.PICK_IMAGE));
    }

    public void setPendingImage(@Nullable String uri) {
        pendingImage.setValue(uri);
    }

    public void clearPendingImage() {
        pendingImage.setValue(null);
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
        previousQuestion = null;
        // 加载期间不能发送，直接替换列表
        commit(new ArrayList<>(history.messages));
        loadState.setValue(UiState.SUCCESS);
        event.setValue(ChatEvent.of(ChatEvent.SCROLL_TO_BOTTOM));
    }

    /**
     * 把 SessionDetailVO 转换为列表项：关联记录存在时第一项为引用卡；用户消息带图时先图片后文字
     * （只发图片时服务器保存的默认问题不显示为文字气泡）；AI 回答保留思考内容与耗时。
     * 同时取最后一条用户消息作为「重新生成」使用的问题（图片已上传，不会重复上传）。
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
                    String image = AiFormat.isBlank(vo.getImageUrl()) ? null : vo.getImageUrl();
                    String content = AiFormat.trim(vo.getContent());
                    long imageId = 0;
                    long textId = 0;
                    if (image != null) {
                        ChatMessage m = ChatMessage.userRemoteImage(image, vo.getCreatedAt());
                        imageId = m.getLocalId();
                        list.add(m);
                    }
                    boolean defaultQuestion = image != null && AiConstants.DEFAULT_IMAGE_QUESTION.equals(content);
                    String text = defaultQuestion ? "" : content;
                    if (!text.isEmpty()) {
                        ChatMessage m = ChatMessage.userText(text, vo.getCreatedAt());
                        textId = m.getLocalId();
                        list.add(m);
                    }
                    if (!content.isEmpty() || image != null) {
                        question = new Question(text, content.isEmpty() ? AiConstants.DEFAULT_IMAGE_QUESTION : content,
                                null, imageId, textId);
                        question.imageUrl = image;
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
        // 两步的错误先转为值再合并：两步同时失败时不会产生无人接收的第二个错误（UndeliverableException）
        Single<Step> imageStep = uploadIfNeeded(q).map(Step::ok).onErrorReturn(Step::fail);
        Single<Step> sessionStep = ensureSession().map(Step::ok).onErrorReturn(Step::fail);
        Disposable task = Single.zip(sessionStep, imageStep, (sessionResult, imageResult) -> {
                    // 图片错误优先：读取失败需要撤回提问，上传失败需要标记图片
                    if (imageResult.error != null) {
                        throw imageResult.error;
                    }
                    if (sessionResult.error != null) {
                        throw sessionResult.error;
                    }
                    return new Target((Long) sessionResult.value, (String) imageResult.value);
                })
                .flatMapPublisher(target -> repository
                        .sendMessage(target.sessionId, q.content, target.imageUrl, regenerate)
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

    /** 已上传过（重试、重新生成、历史对话）直接返回缓存的地址；没有图片返回空串；否则压缩上传 */
    @NonNull
    private Single<String> uploadIfNeeded(@NonNull Question q) {
        String cached = q.imageUrl;
        if (cached != null) {
            return Single.just(cached);
        }
        if (q.localUri == null) {
            return Single.just("");
        }
        updateImage(q.imageMessageId, ChatMessage.IMAGE_UPLOADING, null);
        return repository.uploadImage(q.localUri)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSuccess(url -> {
                    q.imageUrl = url;
                    updateImage(q.imageMessageId, ChatMessage.IMAGE_DONE, url);
                })
                .onErrorResumeNext(error -> Single.error(new ImageUploadException(error)));
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
        Question q = lastQuestion;
        List<ChatMessage> list = copy();

        if (error instanceof ImageUploadException) {
            Throwable cause = error.getCause();
            if (cause instanceof ImageCompressor.ImageReadException) {
                // 图片读取失败：不发送，撤回本次提问
                dropQuestion(list, q);
                showToast(AiConstants.TOAST_IMAGE_READ_FAILED);
                return;
            }
            removeLastAi(list);
            if (q != null) {
                setImage(list, q.imageMessageId, ChatMessage.IMAGE_FAILED, null);
            }
            commit(list);
            int code = ApiException.codeOf(cause);
            showToast(code == ApiException.UNAUTHORIZED || code == ApiException.TOO_FREQUENT
                    ? ApiException.messageOf(cause) : AiConstants.TOAST_IMAGE_UPLOAD_FAILED);
            return;
        }

        resetUploadingImages(list);
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

    private void updateImage(long messageId, int state, @Nullable String url) {
        List<ChatMessage> list = copy();
        if (setImage(list, messageId, state, url)) {
            commit(list);
        }
    }

    private static boolean setImage(@NonNull List<ChatMessage> list, long messageId, int state,
                                    @Nullable String url) {
        for (int i = 0; i < list.size(); i++) {
            ChatMessage m = list.get(i);
            if (m.getLocalId() == messageId) {
                list.set(i, m.withImage(state, url != null ? url : m.getImageUrl()));
                return true;
            }
        }
        return false;
    }

    private static void resetUploadingImages(@NonNull List<ChatMessage> list) {
        for (int i = 0; i < list.size(); i++) {
            ChatMessage m = list.get(i);
            if (m.getImageState() == ChatMessage.IMAGE_UPLOADING) {
                list.set(i, m.withImage(ChatMessage.IMAGE_IDLE, m.getImageUrl()));
            }
        }
    }

    private void removeLastAi(@NonNull List<ChatMessage> list) {
        int last = list.size() - 1;
        if (last >= 0 && list.get(last).getType() == ChatMessage.TYPE_AI) {
            typing.remove(list.remove(last).getLocalId());
        }
    }

    /** 撤回本次提问（图片读取失败）：移除图片、文字与空 AI 气泡，文字放回输入框 */
    private void dropQuestion(@NonNull List<ChatMessage> list, @Nullable Question q) {
        removeLastAi(list);
        if (q != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                long id = list.get(i).getLocalId();
                if (id == q.imageMessageId || id == q.textMessageId) {
                    list.remove(i);
                }
            }
            if (AiFormat.isBlank(inputText.getValue()) && !q.text.isEmpty()) {
                inputText.setValue(q.text);
            }
            lastQuestion = previousQuestion;
            previousQuestion = null;
        }
        commit(list);
    }

    private void updateSendState() {
        boolean busy = isStreaming();
        boolean ready = !isLoading();
        boolean hasInput = !AiFormat.isBlank(inputText.getValue()) || pendingImage.getValue() != null;
        setIfChanged(canSend, hasInput && !busy && ready);
        setIfChanged(actionEnabled, (hasInput && ready) || busy);
    }

    @Override
    protected void onCleared() {
        typing.clear();
        super.onCleared();
    }

    @VisibleForTesting
    void setClock(@NonNull LongSupplier clock) {
        this.clock = clock;
    }

    private static void setIfChanged(@NonNull MutableLiveData<Boolean> data, boolean value) {
        if (!Boolean.valueOf(value).equals(data.getValue())) {
            data.setValue(value);
        }
    }

    // ==================== 内部类型 ====================

    /** 最近一次提问：重新生成与重试时使用 */
    static final class Question {
        /** 用户输入的文字（只发图片时为空串） */
        final String text;
        /** 实际发送的问题 */
        final String content;
        @Nullable
        final String localUri;
        final long imageMessageId;
        final long textMessageId;
        /** 上传后的图片地址，重试、重新生成时不再重复上传 */
        @Nullable
        volatile String imageUrl;

        Question(@NonNull String text, @NonNull String content, @Nullable String localUri, long imageMessageId,
                 long textMessageId) {
            this.text = text;
            this.content = content;
            this.localUri = localUri;
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

    /** 并行步骤（创建会话 / 上传图片）的结果：值或错误 */
    private static final class Step {
        @Nullable
        final Object value;
        @Nullable
        final Throwable error;

        private Step(@Nullable Object value, @Nullable Throwable error) {
            this.value = value;
            this.error = error;
        }

        static Step ok(@NonNull Object value) {
            return new Step(value, null);
        }

        static Step fail(@NonNull Throwable error) {
            return new Step(null, error);
        }
    }

    private static final class Target {
        final long sessionId;
        @Nullable
        final String imageUrl;

        Target(long sessionId, @NonNull String imageUrl) {
            this.sessionId = sessionId;
            this.imageUrl = imageUrl.isEmpty() ? null : imageUrl;
        }
    }

    /** 图片压缩或上传失败（与流式回答的错误区分） */
    static final class ImageUploadException extends RuntimeException {
        ImageUploadException(@NonNull Throwable cause) {
            super(cause);
        }
    }
}
