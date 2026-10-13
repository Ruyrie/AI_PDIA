package com.heban.pestai.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.ai.FakeAiRepository;
import com.heban.pestai.ai.RxSchedulersRule;
import com.heban.pestai.ai.data.StreamBrokenException;
import com.heban.pestai.ai.data.StreamChunk;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.ai.util.ThinkingFormatter;
import com.heban.pestai.base.UiState;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.vo.ChatMessageVO;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.subjects.SingleSubject;

public class ChatViewModelTest {

    private static final String QUESTION = "这个病怎么处理？需要马上打药吗？";
    private static final String ANSWER = "从照片看，叶背霉层比上一张更密。";
    private static final String IMAGE_URI = "content://media/picker/0/com.android.providers.media.photopicker/media/1";
    private static final String IMAGE_URL = "http://10.0.2.2:8080/files/chat/20261013/8f2c.jpg";

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxSchedulersRule rxRule = new RxSchedulersRule();

    private FakeAiRepository repo;
    private ChatViewModel vm;

    @Before
    public void setUp() {
        repo = new FakeAiRepository();
        vm = new ChatViewModel(repo);
        // MediatorLiveData 只有在被观察时才会响应数据源变化
        vm.getCanSend().observeForever(v -> { });
        vm.getActionEnabled().observeForever(v -> { });
    }

    private List<ChatMessage> messages() {
        return vm.getMessages().getValue();
    }

    private ChatMessage last() {
        List<ChatMessage> list = messages();
        return list.get(list.size() - 1);
    }

    private PublishProcessor<StreamChunk> pendingAnswer() {
        PublishProcessor<StreamChunk> processor = PublishProcessor.create();
        repo.answer = call -> processor;
        return processor;
    }

    private static StreamChunk content(String text) {
        return StreamChunk.content(text);
    }

    private static StreamChunk reasoning(String text) {
        return StreamChunk.reasoning(text);
    }

    private void type(String text) {
        vm.getInputText().setValue(text);
    }

    // ==================== 发送与流式输出 ====================

    @Test
    public void initWithQuestion_sendsAndStreamsAnswer() {
        vm.init(ChatArgs.ofQuestion(QUESTION, null));

        List<ChatMessage> list = messages();
        assertEquals(2, list.size());
        assertEquals(ChatMessage.TYPE_USER_TEXT, list.get(0).getType());
        assertEquals(QUESTION, list.get(0).getContent());
        ChatMessage ai = list.get(1);
        assertEquals(ChatMessage.TYPE_AI, ai.getType());
        assertEquals(ANSWER, ai.getContent());
        assertFalse(ai.isStreaming());
        assertTrue(ai.showActions());
        assertTrue(ai.isLatest());
        assertFalse(vm.isStreaming());

        assertEquals(1, repo.sendCalls.size());
        FakeAiRepository.SendCall call = repo.sendCalls.get(0);
        assertEquals(512, call.sessionId);
        assertEquals(QUESTION, call.content);
        assertNull(call.imageUrl);
        assertFalse(call.regenerate);
        assertEquals(Collections.singletonList((Long) null), repo.createSessionRecordIds);
        assertEquals("", vm.getInputText().getValue());
    }

    @Test
    public void chunksAppendToLastAiMessage_andButtonBecomesStop() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofRecord(-1));
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
        type("番茄叶片发黄卷曲是什么原因？");
        assertEquals(Boolean.TRUE, vm.getCanSend().getValue());

        vm.onSendOrStop();
        assertTrue(vm.isStreaming());
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
        assertEquals("生成中按钮为停止按钮，可点击", Boolean.TRUE, vm.getActionEnabled().getValue());
        assertEquals(2, messages().size());
        assertTrue(last().isStreaming());
        assertEquals("", last().getContent());
        assertFalse(last().showActions());

        answer.onNext(content("从照片看，"));
        assertEquals("从照片看，", last().getContent());
        long aiId = last().getLocalId();
        answer.onNext(content("叶背霉层"));
        assertEquals("从照片看，叶背霉层", last().getContent());
        assertEquals("只替换最后一条，localId 不变", aiId, last().getLocalId());
        assertTrue(last().isStreaming());

        // 生成中可以输入但不能发送
        type("再问一个");
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
        vm.send();
        assertEquals(1, repo.sendCalls.size());

        answer.onComplete();
        assertFalse(vm.isStreaming());
        assertFalse(last().isStreaming());
        assertTrue(last().showActions());
        assertEquals(Boolean.TRUE, vm.getCanSend().getValue());
    }

    @Test
    public void stopGenerating_keepsOutput_andCancelsStream() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        answer.onNext(content("建议今天就"));
        assertTrue(answer.hasSubscribers());

        vm.onSendOrStop();

        assertFalse("取消订阅即取消请求", answer.hasSubscribers());
        assertFalse(vm.isStreaming());
        ChatMessage ai = last();
        assertEquals("建议今天就", ai.getContent());
        assertFalse(ai.isStreaming());
        assertEquals(ChatMessage.NOTICE_NONE, ai.getNotice());
        assertTrue(ai.showActions());
        answer.onNext(content("不再追加"));
        assertEquals("建议今天就", last().getContent());
    }

    @Test
    public void stopBeforeFirstChunk_removesEmptyBubble() {
        pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        vm.stopGenerating();
        assertEquals(1, messages().size());
        assertEquals(ChatMessage.TYPE_USER_TEXT, last().getType());
        assertFalse(vm.isStreaming());
    }

    @Test
    public void regenerate_replacesLastAnswer_reusesSession() {
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        long oldId = last().getLocalId();
        repo.answer = call -> FakeAiRepository.contents("新的回答");

        vm.regenerate();

        assertEquals(2, repo.sendCalls.size());
        FakeAiRepository.SendCall call = repo.sendCalls.get(1);
        assertTrue(call.regenerate);
        assertEquals(QUESTION, call.content);
        assertEquals(512, call.sessionId);
        assertEquals("会话只创建一次", 1, repo.createSessionRecordIds.size());
        assertEquals(2, messages().size());
        assertNotEquals(oldId, last().getLocalId());
        assertEquals("新的回答", last().getContent());
    }

    @Test
    public void regenerate_ignoredWhileStreaming() {
        pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        vm.regenerate();
        assertEquals(1, repo.sendCalls.size());
    }

    @Test
    public void latestFlag_onlyOnLastAnswer() {
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        type("今天另一片叶子背面也长了黑霉，是不是更严重了？");
        vm.send();
        List<ChatMessage> list = messages();
        assertEquals(4, list.size());
        assertFalse(list.get(1).isLatest());
        assertTrue(list.get(3).isLatest());
        assertEquals(2, repo.sendCalls.size());
        assertEquals(1, repo.createSessionRecordIds.size());
    }

    // ==================== 错误处理 ====================

    @Test
    public void error50201_keepsContent_andRetryRegenerates() {
        repo.answer = call -> Flowable.concat(FakeAiRepository.contents("结合识别结果"),
                Flowable.<StreamChunk>error(ApiException.of(ApiException.AI_UNAVAILABLE)));
        vm.init(ChatArgs.ofQuestion(QUESTION, null));

        ChatMessage ai = last();
        assertEquals("结合识别结果", ai.getContent());
        assertEquals(ChatMessage.NOTICE_UNAVAILABLE, ai.getNotice());
        assertTrue(ai.isRetryable());
        assertFalse(ai.showActions());
        assertFalse(vm.isStreaming());

        repo.answer = call -> FakeAiRepository.contents(ANSWER);
        vm.retry();
        assertEquals(2, repo.sendCalls.size());
        assertTrue(repo.sendCalls.get(1).regenerate);
        assertEquals(ANSWER, last().getContent());
        assertEquals(ChatMessage.NOTICE_NONE, last().getNotice());
        assertEquals(2, messages().size());
    }

    @Test
    public void streamBroken_showsInterruptedNotice() {
        repo.answer = call -> Flowable.concat(FakeAiRepository.contents("从照片看，"),
                Flowable.<StreamChunk>error(new StreamBrokenException()));
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        assertEquals("从照片看，", last().getContent());
        assertEquals(ChatMessage.NOTICE_BROKEN, last().getNotice());
        assertTrue(last().isRetryable());
    }

    @Test
    public void networkErrorOnCreateSession_showsInterrupted_andRetryCreatesSessionAgain() {
        repo.createSessionResult = Single.error(ApiException.of(ApiException.NETWORK));
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        assertEquals(ChatMessage.NOTICE_BROKEN, last().getNotice());
        assertEquals("", last().getContent());
        assertTrue(repo.sendCalls.isEmpty());

        repo.createSessionResult = null;
        vm.retry();
        assertEquals(2, repo.createSessionRecordIds.size());
        assertEquals(1, repo.sendCalls.size());
        assertTrue(repo.sendCalls.get(0).regenerate);
        assertEquals(ANSWER, last().getContent());
    }

    @Test
    public void tooFrequent_removesEmptyBubble_andToasts() {
        repo.answer = call -> Flowable.error(ApiException.of(ApiException.TOO_FREQUENT));
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        assertEquals(1, messages().size());
        assertEquals(ChatMessage.TYPE_USER_TEXT, last().getType());
        assertEquals("操作太频繁，请稍后再试", vm.getToast().getValue());
        assertFalse(vm.isStreaming());
    }

    @Test
    public void unauthorized_removesEmptyBubble() {
        repo.createSessionResult = Single.error(ApiException.of(ApiException.UNAUTHORIZED));
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        assertEquals(1, messages().size());
        assertEquals("未登录或登录已过期，请重新登录", vm.getToast().getValue());
    }

    @Test
    public void emptyAnswer_showsEmptyNotice() {
        repo.answer = call -> Flowable.empty();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        ChatMessage ai = last();
        assertEquals(ChatMessage.NOTICE_EMPTY, ai.getNotice());
        assertFalse(ai.isStreaming());
        assertFalse(ai.isRetryable());
        assertTrue("仍可重新生成", ai.showActions());
    }

    // ==================== 带图提问 ====================

    @Test
    public void imageOnly_uploadsOnce_andUsesDefaultQuestion() {
        vm.init(ChatArgs.ofQuestion(null, IMAGE_URI));

        List<ChatMessage> list = messages();
        assertEquals(2, list.size());
        ChatMessage image = list.get(0);
        assertEquals(ChatMessage.TYPE_USER_IMAGE, image.getType());
        assertEquals(IMAGE_URI, image.getLocalUri());
        assertEquals(IMAGE_URL, image.getImageUrl());
        assertEquals(ChatMessage.IMAGE_DONE, image.getImageState());
        assertEquals(Collections.singletonList(IMAGE_URI), repo.uploadCalls);

        FakeAiRepository.SendCall call = repo.sendCalls.get(0);
        assertEquals(AiConstants.DEFAULT_IMAGE_QUESTION, call.content);
        assertEquals(IMAGE_URL, call.imageUrl);
        assertNull(vm.getPendingImage().getValue());

        vm.regenerate();
        assertEquals("重新生成不重复上传", 1, repo.uploadCalls.size());
        assertEquals(IMAGE_URL, repo.sendCalls.get(1).imageUrl);
    }

    @Test
    public void imageWithText_addsImageThenText() {
        vm.init(ChatArgs.ofQuestion("今天另一片叶子背面也长了黑霉", IMAGE_URI));
        List<ChatMessage> list = messages();
        assertEquals(3, list.size());
        assertEquals(ChatMessage.TYPE_USER_IMAGE, list.get(0).getType());
        assertEquals(ChatMessage.TYPE_USER_TEXT, list.get(1).getType());
        assertEquals(ChatMessage.TYPE_AI, list.get(2).getType());
        assertEquals("今天另一片叶子背面也长了黑霉", repo.sendCalls.get(0).content);
    }

    @Test
    public void uploadFailure_marksImage_andTapImageRetries() {
        repo.uploadResult = Single.error(ApiException.of(ApiException.NETWORK));
        vm.init(ChatArgs.ofQuestion("叶子背面长了黑霉", IMAGE_URI));

        List<ChatMessage> list = messages();
        assertEquals("空 AI 气泡被移除", 2, list.size());
        ChatMessage image = list.get(0);
        assertEquals(ChatMessage.IMAGE_FAILED, image.getImageState());
        assertEquals(AiConstants.TOAST_IMAGE_UPLOAD_FAILED, vm.getToast().getValue());
        assertTrue(repo.sendCalls.isEmpty());
        assertFalse(vm.isStreaming());

        repo.uploadResult = Single.just(IMAGE_URL);
        vm.onImageClick(image);

        assertEquals(2, repo.uploadCalls.size());
        assertEquals(1, repo.sendCalls.size());
        assertTrue(repo.sendCalls.get(0).regenerate);
        assertEquals(IMAGE_URL, repo.sendCalls.get(0).imageUrl);
        list = messages();
        assertEquals(3, list.size());
        assertEquals(ChatMessage.IMAGE_DONE, list.get(0).getImageState());
        assertEquals(ANSWER, last().getContent());
    }

    @Test
    public void imageReadFailure_withdrawsQuestion_andRestoresText() {
        repo.uploadResult = Single.error(new ImageCompressor.ImageReadException("bad image"));
        vm.init(ChatArgs.ofRecord(-1));
        type("看看这张");
        vm.setPendingImage("content://bad");
        vm.send();

        assertTrue(messages().isEmpty());
        assertEquals(AiConstants.TOAST_IMAGE_READ_FAILED, vm.getToast().getValue());
        assertEquals("看看这张", vm.getInputText().getValue());
        assertTrue(repo.sendCalls.isEmpty());
        assertFalse(vm.isStreaming());
    }

    // ==================== 初始化、引用卡、游客 ====================

    @Test
    public void init_onlyOnce() {
        ChatArgs args = ChatArgs.ofQuestion(QUESTION, null);
        vm.init(args);
        vm.init(args);
        assertEquals(1, repo.sendCalls.size());
        assertEquals(2, messages().size());
    }

    @Test
    public void quote_shownFirst_andSessionCarriesRecordId() {
        repo.recordResult = Single.just(FakeAiRepository.record(2036, "SUCCESS", "黄瓜霜霉病", 0.92, "中度"));
        vm.init(ChatArgs.ofRecord(2036));

        assertEquals(Collections.singletonList(2036L), repo.recordCalls);
        assertEquals(1, messages().size());
        ChatMessage quote = messages().get(0);
        assertEquals(ChatMessage.TYPE_QUOTE, quote.getType());
        RecordItemVO record = quote.getQuote();
        assertEquals("黄瓜霜霉病", record.getTopName());
        assertTrue(repo.sendCalls.isEmpty());

        type(QUESTION);
        vm.send();
        assertEquals(Collections.singletonList(2036L), repo.createSessionRecordIds);
        assertEquals(3, messages().size());
        assertEquals(ChatMessage.TYPE_QUOTE, messages().get(0).getType());

        vm.openQuotedRecord(quote);
        ChatEvent event = vm.getEvent().getValue();
        assertEquals(ChatEvent.OPEN_RECORD, event.type);
        assertEquals(2036, event.recordId);
    }

    @Test
    public void quote_deletedOrFailed_notShown() {
        repo.recordResult = Single.error(ApiException.of(ApiException.PARAM_ERROR));
        vm.init(ChatArgs.ofRecord(2036));
        assertTrue(messages().isEmpty());

        ChatViewModel other = new ChatViewModel(repo);
        repo.recordResult = Single.just(FakeAiRepository.record(2037, "FAILED", null, null, null));
        other.init(ChatArgs.ofRecord(2037));
        assertTrue(other.getMessages().getValue().isEmpty());
    }

    @Test
    public void guest_cannotSend() {
        repo.guest = true;
        List<Integer> events = new java.util.ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        assertTrue(messages().isEmpty());
        assertTrue(repo.sendCalls.isEmpty());

        vm.onPickImageClick();
        assertEquals(Arrays.asList(ChatEvent.NEED_LOGIN, ChatEvent.NEED_LOGIN), events);
    }

    @Test
    public void pickImage_whenLoggedIn() {
        vm.init(ChatArgs.ofRecord(-1));
        vm.onPickImageClick();
        assertEquals(ChatEvent.PICK_IMAGE, vm.getEvent().getValue().type);
        vm.setPendingImage(IMAGE_URI);
        assertEquals(Boolean.TRUE, vm.getCanSend().getValue());
        vm.clearPendingImage();
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
    }

    @Test
    public void tooLongQuestion_truncatedTo500_andToasts() {
        char[] chars = new char[600];
        Arrays.fill(chars, '叶');
        vm.init(ChatArgs.ofQuestion(new String(chars), null));
        assertEquals(AiConstants.TOAST_QUESTION_TOO_LONG, vm.getToast().getValue());
        assertEquals(500, repo.sendCalls.get(0).content.length());
        assertEquals(500, messages().get(0).getContent().length());
    }

    @Test
    public void blankInput_cannotSend() {
        vm.init(ChatArgs.ofRecord(-1));
        type("   ");
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
        assertEquals(Boolean.FALSE, vm.getActionEnabled().getValue());
        vm.send();
        assertTrue(repo.sendCalls.isEmpty());
        assertTrue(messages().isEmpty());
    }

    // ==================== 思考片段 ====================

    @Test
    public void reasoningChunks_fillReasoning_thenFirstContentRecordsThinkingTime() {
        AtomicLong now = new AtomicLong(10_000);
        vm.setClock(now::get);
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));

        ChatMessage ai = last();
        assertEquals("从发送时刻计时", 10_000, ai.getStartedAt());
        assertEquals(ThinkingFormatter.THINKING, ai.thinkingState());
        assertFalse("还没有思考内容时不显示内容区", ai.isReasoningShown());
        assertTrue(vm.getTyping().isTracking(ai.getLocalId()));

        answer.onNext(reasoning("用户问的是黄瓜霜霉病，"));
        answer.onNext(reasoning("需要先通风。"));
        assertEquals("用户问的是黄瓜霜霉病，需要先通风。", last().getReasoning());
        assertEquals("", last().getContent());
        assertEquals(ThinkingFormatter.THINKING, last().thinkingState());
        assertTrue("思考中思考内容默认展开", last().isReasoningShown());
        assertNull(last().getReasoningMs());

        now.set(22_400);
        answer.onNext(content("结合识别结果，"));
        ChatMessage started = last();
        assertEquals(Long.valueOf(12_400), started.getReasoningMs());
        assertEquals(ThinkingFormatter.DONE, started.thinkingState());
        assertFalse("正文开始后自动折叠", started.isReasoningShown());
        assertEquals("已思考 12 秒", ThinkingFormatter.title(started.thinkingState(), started.getReasoningMs()));

        now.set(30_000);
        answer.onNext(content("建议通风。"));
        assertEquals("思考耗时只在第一个正文片段时记录", Long.valueOf(12_400), last().getReasoningMs());
        answer.onComplete();
        assertEquals("结合识别结果，建议通风。", last().getContent());
        assertEquals("用户问的是黄瓜霜霉病，需要先通风。", last().getReasoning());
        assertTrue(last().showActions());
    }

    @Test
    public void noReasoning_thinkingRowHiddenAfterContentStarts() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(null, IMAGE_URI));
        assertEquals("带图提问走识图模型：只显示「正在思考… 已用 N 秒」", ThinkingFormatter.THINKING,
                last().thinkingState());
        answer.onNext(content("从照片看，"));
        assertEquals(ThinkingFormatter.HIDDEN, last().thinkingState());
        assertNull("没有思考内容不记录耗时", last().getReasoningMs());
    }

    @Test
    public void stopWhileThinking_keepsReasoningCollapsedAsStopped() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        answer.onNext(reasoning("先判断病害类型"));
        vm.stopGenerating();

        assertFalse(answer.hasSubscribers());
        assertEquals("有思考内容时保留气泡", 2, messages().size());
        ChatMessage ai = last();
        assertFalse(ai.isStreaming());
        assertEquals("先判断病害类型", ai.getReasoning());
        assertEquals(ThinkingFormatter.STOPPED, ai.thinkingState());
        assertEquals("已停止思考", ThinkingFormatter.title(ai.thinkingState(), ai.getReasoningMs()));
        assertFalse("折叠", ai.isReasoningShown());
        assertEquals(ChatMessage.NOTICE_NONE, ai.getNotice());
        assertTrue("仍可重新生成", ai.showActions());
    }

    @Test
    public void stopAfterContent_titleKeepsThinkingSeconds() {
        AtomicLong now = new AtomicLong(1_000);
        vm.setClock(now::get);
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        answer.onNext(reasoning("思考"));
        now.set(4_600);
        answer.onNext(content("建议今天就"));
        vm.stopGenerating();
        ChatMessage ai = last();
        assertEquals(ThinkingFormatter.DONE, ai.thinkingState());
        assertEquals("已思考 4 秒", ThinkingFormatter.title(ai.thinkingState(), ai.getReasoningMs()));
    }

    @Test
    public void toggleReasoning_onlyAfterCollapse() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        answer.onNext(reasoning("思考"));
        vm.toggleReasoning(last());
        assertFalse("思考中不响应", last().isReasoningExpanded());

        answer.onNext(content("回答"));
        answer.onComplete();
        assertFalse(last().isReasoningShown());
        vm.toggleReasoning(last());
        assertTrue(last().isReasoningExpanded());
        assertTrue(last().isReasoningShown());
        vm.toggleReasoning(last());
        assertFalse(last().isReasoningShown());
    }

    @Test
    public void typingTracker_followsAnswerLifecycle() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        long id = last().getLocalId();
        TypingTracker typing = vm.getTyping();
        assertTrue(typing.isTracking(id));

        answer.onNext(content("从照片看，"));
        assertEquals("还没有推进帧时不显示", 0, typing.contentShown(id, 5));
        answer.onComplete();
        // 收到 [DONE] 后 0.3 秒内显示完，淡入结束后不再跟踪
        long t = 1_000_000_000L;
        for (int i = 0; i <= 20; i++) {
            typing.step(t + i * 33_000_000L);
        }
        assertFalse(typing.isTracking(id));
        assertEquals(5, typing.contentShown(id, 5));
        assertEquals(Collections.singletonList(id), typing.drainCompleted());
    }

    @Test
    public void retry_dropsOldTypingEntry_andTracksNewAnswer() {
        repo.answer = call -> Flowable.concat(FakeAiRepository.contents("从照片看，"),
                Flowable.<StreamChunk>error(new StreamBrokenException()));
        vm.init(ChatArgs.ofQuestion(QUESTION, null));
        long oldId = last().getLocalId();
        assertTrue("中断后剩余内容仍在显示", vm.getTyping().isTracking(oldId));

        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.retry();
        assertFalse(vm.getTyping().isTracking(oldId));
        assertTrue(vm.getTyping().isTracking(last().getLocalId()));
        answer.onComplete();
    }

    // ==================== 打开历史对话 ====================

    private static ChatMessageVO vo(String role, String content, String imageUrl, String reasoning,
                                    Integer reasoningMs) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setRole(role);
        vo.setContent(content);
        vo.setImageUrl(imageUrl);
        vo.setReasoningContent(reasoning);
        vo.setReasoningMs(reasoningMs);
        vo.setCreatedAt(new Date());
        return vo;
    }

    private static SessionDetailVO detail() {
        SessionDetailVO d = new SessionDetailVO();
        d.setSessionId(700);
        d.setRecordId(2036L);
        d.setTitle("追问：黄瓜霜霉病");
        d.setRecord(FakeAiRepository.record(2036, "SUCCESS", "黄瓜霜霉病", 0.92, "中度"));
        d.setMessages(Arrays.asList(
                vo("user", "这个病怎么处理？", null, null, null),
                vo("assistant", "建议通风。", null, "先看症状", 12_400),
                vo("user", AiConstants.DEFAULT_IMAGE_QUESTION, IMAGE_URL, null, null),
                vo("assistant", "从照片看，霉层更密。", null, null, null)));
        return d;
    }

    @Test
    public void openSession_rendersHistory_withoutCreatingSession() {
        repo.sessionResult = Single.just(detail());
        vm.init(ChatArgs.ofSession(700));

        assertEquals(Collections.singletonList(700L), repo.sessionCalls);
        assertEquals(UiState.SUCCESS, vm.getLoadState().getValue());
        List<ChatMessage> list = messages();
        assertEquals(5, list.size());
        assertEquals(ChatMessage.TYPE_QUOTE, list.get(0).getType());
        assertEquals("黄瓜霜霉病", list.get(0).getQuote().getTopName());
        assertEquals(ChatMessage.TYPE_USER_TEXT, list.get(1).getType());
        assertEquals("这个病怎么处理？", list.get(1).getContent());

        ChatMessage firstAi = list.get(2);
        assertEquals("先看症状", firstAi.getReasoning());
        assertEquals(Long.valueOf(12_400), firstAi.getReasoningMs());
        assertEquals("历史消息显示折叠的「已思考 N 秒」", ThinkingFormatter.DONE, firstAi.thinkingState());
        assertEquals("已思考 12 秒", ThinkingFormatter.title(firstAi.thinkingState(), firstAi.getReasoningMs()));
        assertFalse(firstAi.isReasoningShown());
        assertFalse(firstAi.isLatest());

        ChatMessage image = list.get(3);
        assertEquals("只发图片时默认问题不显示为文字气泡", ChatMessage.TYPE_USER_IMAGE, image.getType());
        assertEquals(IMAGE_URL, image.getImageUrl());
        ChatMessage lastAi = list.get(4);
        assertEquals(ThinkingFormatter.HIDDEN, lastAi.thinkingState());
        assertTrue(lastAi.isLatest());
        assertTrue(lastAi.showActions());
        assertFalse("历史消息直接完整显示", vm.getTyping().isTracking(lastAi.getLocalId()));

        assertTrue(repo.createSessionRecordIds.isEmpty());
        assertTrue("引用卡来自会话详情，不再单独请求", repo.recordCalls.isEmpty());
        assertEquals(ChatEvent.SCROLL_TO_BOTTOM, vm.getEvent().getValue().type);
    }

    @Test
    public void openSession_continueAsking_appendsToSameSession() {
        repo.sessionResult = Single.just(detail());
        vm.init(ChatArgs.ofSession(700));
        type("今天又多了几片病叶");
        vm.send();

        assertEquals(1, repo.sendCalls.size());
        FakeAiRepository.SendCall call = repo.sendCalls.get(0);
        assertEquals(700, call.sessionId);
        assertEquals("今天又多了几片病叶", call.content);
        assertFalse(call.regenerate);
        assertTrue("不创建新会话", repo.createSessionRecordIds.isEmpty());
        assertEquals(7, messages().size());
        assertEquals(ANSWER, last().getContent());
        assertFalse(messages().get(4).isLatest());
        assertTrue(last().isLatest());
    }

    @Test
    public void openSession_regenerateLastAnswer_reusesUploadedImage() {
        repo.sessionResult = Single.just(detail());
        vm.init(ChatArgs.ofSession(700));
        repo.answer = call -> FakeAiRepository.contents("新的回答");
        vm.regenerate();

        assertEquals(1, repo.sendCalls.size());
        FakeAiRepository.SendCall call = repo.sendCalls.get(0);
        assertEquals(700, call.sessionId);
        assertEquals(AiConstants.DEFAULT_IMAGE_QUESTION, call.content);
        assertEquals(IMAGE_URL, call.imageUrl);
        assertTrue(call.regenerate);
        assertTrue("图片已在服务器上，不重复上传", repo.uploadCalls.isEmpty());
        assertEquals(5, messages().size());
        assertEquals("新的回答", last().getContent());
    }

    @Test
    public void openSession_stoppedBeforeAnswer_showsStoppedThinkingOnly() {
        SessionDetailVO d = new SessionDetailVO();
        d.setSessionId(701);
        d.setTitle("蚜虫怎么防？");
        d.setMessages(Arrays.asList(
                vo("user", "蚜虫怎么防？", null, null, null),
                vo("assistant", "", null, "先考虑药剂与天敌", null),
                vo("user", "继续", null, null, null),
                vo("assistant", "可以用吡虫啉。", null, "想一想", null)));
        repo.sessionResult = Single.just(d);
        vm.init(ChatArgs.ofSession(701));

        List<ChatMessage> list = messages();
        assertEquals("记录已删除（record 为空）不显示引用卡", 4, list.size());
        ChatMessage stopped = list.get(1);
        assertEquals(ThinkingFormatter.STOPPED, stopped.thinkingState());
        assertEquals("已停止思考", ThinkingFormatter.title(stopped.thinkingState(), stopped.getReasoningMs()));
        assertEquals("不显示「AI 暂时没有给出回答」", ChatMessage.NOTICE_NONE, stopped.getNotice());
        assertFalse(stopped.hasContent());
        assertFalse(stopped.isReasoningShown());

        ChatMessage noMs = list.get(3);
        assertEquals(ThinkingFormatter.DONE, noMs.thinkingState());
        assertEquals("reasoning_ms 为空时不显示秒数", "已思考",
                ThinkingFormatter.title(noMs.thinkingState(), noMs.getReasoningMs()));
    }

    @Test
    public void openSession_loadingBlocksSending_andFailureCanRetry() {
        SingleSubject<SessionDetailVO> pending = SingleSubject.create();
        repo.sessionResult = pending;
        vm.init(ChatArgs.ofSession(700));
        assertEquals(UiState.LOADING, vm.getLoadState().getValue());
        type("还能发吗");
        assertEquals("加载中不能发送", Boolean.FALSE, vm.getCanSend().getValue());
        assertEquals(Boolean.FALSE, vm.getActionEnabled().getValue());
        vm.send();
        assertTrue(repo.sendCalls.isEmpty());

        pending.onError(ApiException.of(ApiException.NETWORK));
        assertEquals(UiState.ERROR, vm.getLoadState().getValue());
        assertEquals(ApiException.defaultMessage(ApiException.NETWORK), vm.getLoadError().getValue());
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
        assertTrue(messages().isEmpty());

        repo.sessionResult = Single.just(detail());
        vm.retryLoad();
        assertEquals(2, repo.sessionCalls.size());
        assertEquals(UiState.SUCCESS, vm.getLoadState().getValue());
        assertEquals(5, messages().size());
        assertEquals("输入的文字保留，加载完成后可发送", Boolean.TRUE, vm.getCanSend().getValue());
    }

    @Test
    public void openSession_onlyOnce_onRotation() {
        repo.sessionResult = Single.just(detail());
        vm.init(ChatArgs.ofSession(700));
        vm.init(ChatArgs.ofSession(700));
        assertEquals(1, repo.sessionCalls.size());
    }
}
