package com.heban.pestai.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.ai.FakeAiRepository;
import com.heban.pestai.ai.FakeSharedPreferences;
import com.heban.pestai.ai.RxSchedulersRule;
import com.heban.pestai.ai.compose.ComposerButtons;
import com.heban.pestai.ai.compose.InputModeStore;
import com.heban.pestai.ai.compose.PendingImage;
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
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String IMAGE_URI_2 = "content://media/picker/0/com.android.providers.media.photopicker/media/2";
    private static final String IMAGE_URL_2 = "http://10.0.2.2:8080/files/chat/20261014/9a1d.jpg";

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxSchedulersRule rxRule = new RxSchedulersRule();
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private FakeAiRepository repo;
    /** 记住的输入方式（Q01 / Q02 共用同一个文件） */
    private InputModeStore modeStore;
    private ChatViewModel vm;

    @Before
    public void setUp() {
        repo = new FakeAiRepository();
        modeStore = new InputModeStore(new FakeSharedPreferences());
        vm = new ChatViewModel(repo, modeStore);
        // MediatorLiveData 只有在被观察时才会响应数据源变化
        vm.getComposerButtons().observeForever(v -> { });
    }

    private ComposerButtons buttons() {
        return vm.getComposerButtons().getValue();
    }

    /** 点右侧按钮会发送（可点且不是停止按钮） */
    private boolean canSend() {
        return buttons().canSend();
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
        vm.init(ChatArgs.ofQuestion(QUESTION));

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
        assertTrue(call.imageUrls.isEmpty());
        assertFalse(call.regenerate);
        assertEquals(Collections.singletonList((Long) null), repo.createSessionRecordIds);
        assertEquals("", vm.getInputText().getValue());
    }

    @Test
    public void chunksAppendToLastAiMessage_andButtonBecomesStop() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofRecord(-1));
        assertFalse(canSend());
        type("番茄叶片发黄卷曲是什么原因？");
        assertTrue(canSend());

        vm.onActionClick();
        assertTrue(vm.isStreaming());
        assertFalse(canSend());
        assertTrue("生成中按钮为停止按钮，可点击", buttons().isActionEnabled());
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
        assertFalse(canSend());
        vm.send();
        assertEquals(1, repo.sendCalls.size());

        answer.onComplete();
        assertFalse(vm.isStreaming());
        assertFalse(last().isStreaming());
        assertTrue(last().showActions());
        assertTrue(canSend());
    }

    @Test
    public void composerButtons_stopShownWhileStreaming_regardlessOfText() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofRecord(-1));
        assertEquals("空输入：相机与「+」，没有发送按钮", ComposerButtons.EMPTY, buttons());
        assertFalse(buttons().isActionVisible());

        type(QUESTION);
        assertFalse(buttons().isCameraVisible());
        assertFalse(buttons().isAlbumVisible());
        assertTrue(buttons().isActionVisible());
        assertFalse(buttons().isStop());

        vm.onActionClick();
        assertTrue(vm.isStreaming());
        assertTrue("输入框已清空，生成中仍显示停止按钮", buttons().isActionVisible());
        assertTrue(buttons().isStop());
        assertTrue(buttons().isActionEnabled());
        assertTrue(buttons().isCameraVisible());
        assertTrue(buttons().isAlbumVisible());

        type("再问一个");
        assertTrue(buttons().isStop());
        assertFalse("相机与「+」仍按文字规则", buttons().isCameraVisible());
        assertFalse(canSend());

        vm.onActionClick();
        assertFalse("生成中点按为停止生成", vm.isStreaming());
        assertFalse(answer.hasSubscribers());
        assertEquals(1, repo.sendCalls.size());
        assertFalse(buttons().isStop());
        assertTrue(canSend());
        assertEquals("再问一个", vm.getInputText().getValue());
    }

    @Test
    public void stopGenerating_keepsOutput_andCancelsStream() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION));
        answer.onNext(content("建议今天就"));
        assertTrue(answer.hasSubscribers());

        vm.onActionClick();

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
        vm.init(ChatArgs.ofQuestion(QUESTION));
        vm.stopGenerating();
        assertEquals(1, messages().size());
        assertEquals(ChatMessage.TYPE_USER_TEXT, last().getType());
        assertFalse(vm.isStreaming());
    }

    @Test
    public void regenerate_replacesLastAnswer_reusesSession() {
        vm.init(ChatArgs.ofQuestion(QUESTION));
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
        vm.init(ChatArgs.ofQuestion(QUESTION));
        vm.regenerate();
        assertEquals(1, repo.sendCalls.size());
    }

    @Test
    public void latestFlag_onlyOnLastAnswer() {
        vm.init(ChatArgs.ofQuestion(QUESTION));
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
        vm.init(ChatArgs.ofQuestion(QUESTION));

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
        vm.init(ChatArgs.ofQuestion(QUESTION));
        assertEquals("从照片看，", last().getContent());
        assertEquals(ChatMessage.NOTICE_BROKEN, last().getNotice());
        assertTrue(last().isRetryable());
    }

    @Test
    public void networkErrorOnCreateSession_showsInterrupted_andRetryCreatesSessionAgain() {
        repo.createSessionResult = Single.error(ApiException.of(ApiException.NETWORK));
        vm.init(ChatArgs.ofQuestion(QUESTION));
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
        vm.init(ChatArgs.ofQuestion(QUESTION));
        assertEquals(1, messages().size());
        assertEquals(ChatMessage.TYPE_USER_TEXT, last().getType());
        assertEquals("操作太频繁，请稍后再试", vm.getToast().getValue());
        assertFalse(vm.isStreaming());
    }

    @Test
    public void unauthorized_removesEmptyBubble() {
        repo.createSessionResult = Single.error(ApiException.of(ApiException.UNAUTHORIZED));
        vm.init(ChatArgs.ofQuestion(QUESTION));
        assertEquals(1, messages().size());
        assertEquals("未登录或登录已过期，请重新登录", vm.getToast().getValue());
    }

    @Test
    public void emptyAnswer_showsEmptyNotice() {
        repo.answer = call -> Flowable.empty();
        vm.init(ChatArgs.ofQuestion(QUESTION));
        ChatMessage ai = last();
        assertEquals(ChatMessage.NOTICE_EMPTY, ai.getNotice());
        assertFalse(ai.isStreaming());
        assertFalse(ai.isRetryable());
        assertTrue("仍可重新生成", ai.showActions());
    }

    // ==================== 带图提问 ====================

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }

    @Test
    public void initWithUploadedImages_sendsWithoutUploading_andContentEmpty() {
        vm.init(ChatArgs.ofQuestion(null, list(IMAGE_URI, IMAGE_URI_2), list(IMAGE_URL, IMAGE_URL_2)));

        List<ChatMessage> list = messages();
        assertEquals("只发图片没有文字气泡", 2, list.size());
        ChatMessage images = list.get(0);
        assertEquals(ChatMessage.TYPE_USER_IMAGE, images.getType());
        assertEquals("全部图片是一个列表项", list(IMAGE_URL, IMAGE_URL_2), images.getImageUrls());
        assertEquals(list(IMAGE_URI, IMAGE_URI_2), images.getLocalUris());
        assertEquals("优先显示本地图片", list(IMAGE_URI, IMAGE_URI_2), images.getImageSources());
        assertTrue("Q01 已上传，不再上传", repo.uploadCalls.isEmpty());

        FakeAiRepository.SendCall call = repo.sendCalls.get(0);
        assertEquals("只发图片时 content 为空串", "", call.content);
        assertEquals(list(IMAGE_URL, IMAGE_URL_2), call.imageUrls);
        assertFalse(call.regenerate);
        assertEquals(ANSWER, last().getContent());

        vm.regenerate();
        FakeAiRepository.SendCall again = repo.sendCalls.get(1);
        assertTrue(again.regenerate);
        assertEquals("", again.content);
        assertEquals("重新生成原样重发图片地址", list(IMAGE_URL, IMAGE_URL_2), again.imageUrls);
        assertTrue(repo.uploadCalls.isEmpty());
    }

    @Test
    public void initWithImagesAndText_addsImagesThenText() {
        vm.init(ChatArgs.ofQuestion("今天另一片叶子背面也长了黑霉", list(IMAGE_URI), list(IMAGE_URL)));
        List<ChatMessage> list = messages();
        assertEquals(3, list.size());
        assertEquals(ChatMessage.TYPE_USER_IMAGE, list.get(0).getType());
        assertEquals(ChatMessage.TYPE_USER_TEXT, list.get(1).getType());
        assertEquals(ChatMessage.TYPE_AI, list.get(2).getType());
        assertEquals("今天另一片叶子背面也长了黑霉", repo.sendCalls.get(0).content);
        assertEquals(list(IMAGE_URL), repo.sendCalls.get(0).imageUrls);
    }

    @Test
    public void composerImages_uploadImmediately_andSendInOrder() {
        Map<String, SingleSubject<String>> uploads = new LinkedHashMap<>();
        repo.upload = uri -> {
            SingleSubject<String> subject = SingleSubject.create();
            uploads.put(uri, subject);
            return subject;
        };
        vm.init(ChatArgs.ofRecord(-1));
        vm.onImagesPicked(list(IMAGE_URI, IMAGE_URI_2));
        assertEquals("选好即上传", list(IMAGE_URI, IMAGE_URI_2), repo.uploadCalls);
        assertEquals(Integer.valueOf(2), vm.getImageCount().getValue());
        assertTrue("只有图片也能点发送", canSend());

        // 第二张先完成：发送顺序仍按加入顺序
        uploads.get(IMAGE_URI_2).onSuccess(IMAGE_URL_2);
        uploads.get(IMAGE_URI).onSuccess(IMAGE_URL);
        type("叶子背面长了黑霉");
        vm.send();

        FakeAiRepository.SendCall call = repo.sendCalls.get(0);
        assertEquals("叶子背面长了黑霉", call.content);
        assertEquals(list(IMAGE_URL, IMAGE_URL_2), call.imageUrls);
        assertEquals("发送不再上传", 2, repo.uploadCalls.size());
        List<ChatMessage> list = messages();
        assertEquals(3, list.size());
        assertEquals(list(IMAGE_URI, IMAGE_URI_2), list.get(0).getLocalUris());
        assertEquals(list(IMAGE_URL, IMAGE_URL_2), list.get(0).getImageUrls());
        assertTrue("发送后清空图片队列", vm.getImages().getValue().isEmpty());
        assertEquals("", vm.getInputText().getValue());
    }

    @Test
    public void send_blockedWhileUploading() {
        SingleSubject<String> upload = SingleSubject.create();
        repo.upload = uri -> upload;
        vm.init(ChatArgs.ofRecord(-1));
        type("看看这张");
        vm.onImagesPicked(list(IMAGE_URI));
        assertTrue(canSend());

        vm.send();
        assertEquals(AiConstants.TOAST_IMAGES_UPLOADING, vm.getToast().getValue());
        assertTrue(repo.sendCalls.isEmpty());
        assertTrue(messages().isEmpty());
        assertEquals("看看这张", vm.getInputText().getValue());
        assertEquals(1, vm.getImages().getValue().size());

        upload.onSuccess(IMAGE_URL);
        vm.send();
        assertEquals(1, repo.sendCalls.size());
        assertEquals(list(IMAGE_URL), repo.sendCalls.get(0).imageUrls);
    }

    @Test
    public void send_blockedWithFailedImage_andRetryThenSend() {
        repo.uploadResult = Single.error(ApiException.of(ApiException.NETWORK));
        vm.init(ChatArgs.ofRecord(-1));
        vm.onImagesPicked(list(IMAGE_URI));
        assertEquals(AiConstants.TOAST_IMAGE_UPLOAD_FAILED, vm.getToast().getValue());
        PendingImage failed = vm.getImages().getValue().get(0);
        assertTrue(failed.isFailed());

        vm.send();
        assertEquals(AiConstants.TOAST_IMAGES_FAILED, vm.getToast().getValue());
        assertTrue(repo.sendCalls.isEmpty());

        repo.uploadResult = Single.just(IMAGE_URL);
        vm.retryImage(failed.getId());
        assertEquals(2, repo.uploadCalls.size());
        vm.send();
        FakeAiRepository.SendCall call = repo.sendCalls.get(0);
        assertEquals("只发图片时 content 为空串", "", call.content);
        assertEquals(list(IMAGE_URL), call.imageUrls);
        assertEquals("只有图片项与 AI 气泡", 2, messages().size());
    }

    @Test
    public void imageReadFailure_removesImageFromComposer() {
        repo.uploadResult = Single.error(new ImageCompressor.ImageReadException("bad image"));
        vm.init(ChatArgs.ofRecord(-1));
        type("看看这张");
        vm.onImagesPicked(list("content://bad"));

        assertTrue(vm.getImages().getValue().isEmpty());
        assertEquals(AiConstants.TOAST_IMAGE_READ_FAILED, vm.getToast().getValue());
        assertEquals("看看这张", vm.getInputText().getValue());
        assertTrue(messages().isEmpty());
        assertFalse(vm.isStreaming());
    }

    @Test
    public void removeImage_cancelsUpload() {
        SingleSubject<String> upload = SingleSubject.create();
        repo.upload = uri -> upload;
        vm.init(ChatArgs.ofRecord(-1));
        vm.onImagesPicked(list(IMAGE_URI));
        assertTrue(upload.hasObservers());
        vm.removeImage(vm.getImages().getValue().get(0).getId());
        assertFalse(upload.hasObservers());
        assertFalse(canSend());
    }

    @Test
    public void addImages_whileStreaming_thenSendAfterAnswer() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION));
        vm.onImagesPicked(list(IMAGE_URI));
        assertFalse("生成中可以添加图片但不能发送", canSend());
        answer.onComplete();
        assertTrue(canSend());
    }

    // ==================== 初始化、引用卡、游客 ====================

    @Test
    public void init_onlyOnce() {
        ChatArgs args = ChatArgs.ofQuestion(QUESTION);
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

        ChatViewModel other = new ChatViewModel(repo, modeStore);
        repo.recordResult = Single.just(FakeAiRepository.record(2037, "FAILED", null, null, null));
        other.init(ChatArgs.ofRecord(2037));
        assertTrue(other.getMessages().getValue().isEmpty());
    }

    @Test
    public void guest_cannotSend() {
        repo.guest = true;
        List<Integer> events = new java.util.ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));
        vm.init(ChatArgs.ofQuestion(QUESTION));
        assertTrue(messages().isEmpty());
        assertTrue(repo.sendCalls.isEmpty());

        vm.onCameraClick();
        vm.onAlbumClick();
        assertEquals(Arrays.asList(ChatEvent.NEED_LOGIN, ChatEvent.NEED_LOGIN, ChatEvent.NEED_LOGIN), events);
        vm.onImagesPicked(list(IMAGE_URI));
        assertTrue(repo.uploadCalls.isEmpty());
    }

    @Test
    public void addImage_whenLoggedIn_cameraTakesPhoto_plusOpensAlbum() {
        vm.init(ChatArgs.ofRecord(-1));
        List<Integer> events = new ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));
        vm.onCameraClick();
        vm.onAlbumClick();
        assertEquals("不再弹出来源面板，直接拍照 / 打开相册", Arrays.asList(ChatEvent.TAKE_PHOTO, ChatEvent.PICK_IMAGE), events);
        assertEquals(AiConstants.MAX_IMAGES, vm.remainingImages());

        vm.onImagesPicked(list(IMAGE_URI));
        assertTrue(canSend());
        assertEquals(8, vm.remainingImages());
        vm.removeImage(vm.getImages().getValue().get(0).getId());
        assertFalse(canSend());
    }

    @Test
    public void addImage_whenFull_toastsInsteadOfOpeningPicker() {
        repo.upload = FakeAiRepository::urlSingle;
        vm.init(ChatArgs.ofRecord(-1));
        vm.onImagesPicked(list("c://1", "c://2", "c://3", "c://4", "c://5", "c://6", "c://7", "c://8", "c://9"));
        assertTrue("已满 9 张：相机与「+」变淡", buttons().isAddDimmed());
        assertTrue(buttons().isCameraVisible());
        List<Integer> events = new ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));

        vm.onCameraClick();
        vm.onAlbumClick();
        assertTrue(events.isEmpty());
        assertEquals(AiConstants.TOAST_TOO_MANY_IMAGES, vm.getToast().getValue());

        vm.send();
        assertEquals(9, repo.sendCalls.get(0).imageUrls.size());
        assertFalse(buttons().isAddDimmed());
    }

    @Test
    public void tooLongQuestion_truncatedTo500_andToasts() {
        char[] chars = new char[600];
        Arrays.fill(chars, '叶');
        vm.init(ChatArgs.ofQuestion(new String(chars)));
        assertEquals(AiConstants.TOAST_QUESTION_TOO_LONG, vm.getToast().getValue());
        assertEquals(500, repo.sendCalls.get(0).content.length());
        assertEquals(500, messages().get(0).getContent().length());
    }

    @Test
    public void blankInput_cannotSend() {
        vm.init(ChatArgs.ofRecord(-1));
        type("   ");
        assertFalse(canSend());
        assertFalse(buttons().isActionEnabled());
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
        vm.init(ChatArgs.ofQuestion(QUESTION));

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
        vm.init(ChatArgs.ofQuestion(null, list(IMAGE_URI), list(IMAGE_URL)));
        assertEquals("带图提问走识图模型：只显示「正在思考… 已用 N 秒」", ThinkingFormatter.THINKING,
                last().thinkingState());
        answer.onNext(content("从照片看，"));
        assertEquals(ThinkingFormatter.HIDDEN, last().thinkingState());
        assertNull("没有思考内容不记录耗时", last().getReasoningMs());
    }

    @Test
    public void stopWhileThinking_keepsReasoningCollapsedAsStopped() {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION));
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
        vm.init(ChatArgs.ofQuestion(QUESTION));
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
        vm.init(ChatArgs.ofQuestion(QUESTION));
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
        vm.init(ChatArgs.ofQuestion(QUESTION));
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
        vm.init(ChatArgs.ofQuestion(QUESTION));
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

    /** 新版本的用户消息：image_urls + 第一张的 image_url */
    private static ChatMessageVO userImages(String content, List<String> imageUrls) {
        ChatMessageVO vo = vo("user", content, imageUrls.isEmpty() ? null : imageUrls.get(0), null, null);
        vo.setImageUrls(imageUrls);
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
        assertEquals("旧数据只发图片时默认问题不显示为文字气泡", ChatMessage.TYPE_USER_IMAGE, image.getType());
        assertEquals("没有 image_urls 时用旧字段 image_url", Collections.singletonList(IMAGE_URL), image.getImageUrls());
        assertTrue("历史消息只有服务器地址", image.getLocalUris().isEmpty());
        assertEquals(Collections.singletonList(IMAGE_URL), image.getImageSources());
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
        assertEquals("旧数据原样重发默认问题", AiConstants.DEFAULT_IMAGE_QUESTION, call.content);
        assertEquals(Collections.singletonList(IMAGE_URL), call.imageUrls);
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
        assertFalse("加载中不能发送", canSend());
        assertFalse(buttons().isActionEnabled());
        vm.send();
        assertTrue(repo.sendCalls.isEmpty());

        pending.onError(ApiException.of(ApiException.NETWORK));
        assertEquals(UiState.ERROR, vm.getLoadState().getValue());
        assertEquals(ApiException.defaultMessage(ApiException.NETWORK), vm.getLoadError().getValue());
        assertFalse(canSend());
        assertTrue(messages().isEmpty());

        repo.sessionResult = Single.just(detail());
        vm.retryLoad();
        assertEquals(2, repo.sessionCalls.size());
        assertEquals(UiState.SUCCESS, vm.getLoadState().getValue());
        assertEquals(5, messages().size());
        assertTrue("输入的文字保留，加载完成后可发送", canSend());
    }

    @Test
    public void openSession_multiImageMessages() {
        SessionDetailVO d = new SessionDetailVO();
        d.setSessionId(702);
        d.setTitle("图片提问");
        d.setMessages(Arrays.asList(
                userImages("", list(IMAGE_URL, IMAGE_URL_2)),
                vo("assistant", "两张照片都像霜霉病。", null, null, null),
                userImages("第三张是新叶", list("http://x/3.jpg")),
                vo("assistant", "新叶也有。", null, null, null)));
        repo.sessionResult = Single.just(d);
        vm.init(ChatArgs.ofSession(702));

        List<ChatMessage> list = messages();
        assertEquals(5, list.size());
        assertEquals(ChatMessage.TYPE_USER_IMAGE, list.get(0).getType());
        assertEquals("image_urls 优先", list(IMAGE_URL, IMAGE_URL_2), list.get(0).getImageUrls());
        assertEquals("只发图片（content 为空）没有文字气泡", ChatMessage.TYPE_AI, list.get(1).getType());
        assertEquals(ChatMessage.TYPE_USER_IMAGE, list.get(2).getType());
        assertEquals(ChatMessage.TYPE_USER_TEXT, list.get(3).getType());
        assertEquals("第三张是新叶", list.get(3).getContent());

        repo.answer = call -> FakeAiRepository.contents("新的回答");
        vm.regenerate();
        FakeAiRepository.SendCall call = repo.sendCalls.get(0);
        assertEquals("第三张是新叶", call.content);
        assertEquals(list("http://x/3.jpg"), call.imageUrls);
        assertTrue(call.regenerate);
    }

    @Test
    public void buildHistory_imageOnlyNewMessage_regeneratesWithEmptyContent() {
        SessionDetailVO d = new SessionDetailVO();
        d.setMessages(Arrays.asList(
                userImages("", list(IMAGE_URL, IMAGE_URL_2)),
                vo("assistant", "回答", null, null, null)));
        ChatViewModel.History history = ChatViewModel.buildHistory(d);
        ChatViewModel.Question q = history.lastQuestion;
        assertEquals("", q.text);
        assertEquals("", q.content);
        assertEquals(list(IMAGE_URL, IMAGE_URL_2), q.imageUrls);
        assertEquals(history.messages.get(0).getLocalId(), q.imageMessageId);
        assertEquals(0, q.textMessageId);
    }

    @Test
    public void imageUrlsOf_prefersListThenLegacyField() {
        ChatMessageVO both = userImages("看看", list(IMAGE_URL, IMAGE_URL_2));
        assertEquals(list(IMAGE_URL, IMAGE_URL_2), ChatViewModel.imageUrlsOf(both));

        ChatMessageVO legacy = vo("user", "看看", IMAGE_URL, null, null);
        assertEquals("旧服务器不返回 image_urls", list(IMAGE_URL), ChatViewModel.imageUrlsOf(legacy));
        legacy.setImageUrls(Collections.emptyList());
        assertEquals("image_urls 为空数组时也退回 image_url", list(IMAGE_URL), ChatViewModel.imageUrlsOf(legacy));

        ChatMessageVO blanks = vo("user", "看看", null, null, null);
        blanks.setImageUrls(Arrays.asList(" ", null, IMAGE_URL_2));
        assertEquals("去掉空值", list(IMAGE_URL_2), ChatViewModel.imageUrlsOf(blanks));

        assertTrue(ChatViewModel.imageUrlsOf(vo("user", "看看", null, null, null)).isEmpty());
    }

    @Test
    public void legacyDefaultQuestion_withoutImage_isShownAsText() {
        SessionDetailVO d = new SessionDetailVO();
        d.setMessages(Collections.singletonList(vo("user", AiConstants.DEFAULT_IMAGE_QUESTION, null, null, null)));
        ChatViewModel.History history = ChatViewModel.buildHistory(d);
        assertEquals(1, history.messages.size());
        assertEquals(ChatMessage.TYPE_USER_TEXT, history.messages.get(0).getType());
    }

    @Test
    public void openSession_onlyOnce_onRotation() {
        repo.sessionResult = Single.just(detail());
        vm.init(ChatArgs.ofSession(700));
        vm.init(ChatArgs.ofSession(700));
        assertEquals(1, repo.sessionCalls.size());
    }

    // ==================== 语音输入 ====================

    @Test
    public void voice_worksWhileStreaming_onlyFillsText() throws IOException {
        PublishProcessor<StreamChunk> answer = pendingAnswer();
        vm.init(ChatArgs.ofQuestion(QUESTION));
        assertTrue(vm.isStreaming());
        assertTrue("生成中麦克风仍显示", buttons().isMicVisible());
        List<String> texts = new ArrayList<>();
        vm.getVoiceText().observeForever(texts::add);
        repo.asrResult = Single.just("那需要打什么药？");
        File wav = temp.newFile("voice.wav");

        assertTrue("生成中也可以录音", vm.onVoicePress());
        vm.recognizeSpeech(wav);

        assertEquals(Collections.singletonList("那需要打什么药？"), texts);
        assertFalse(wav.exists());
        assertEquals("只填文字，不发送", 1, repo.sendCalls.size());
        assertTrue("不影响正在生成的回答", vm.isStreaming());
        answer.onNext(content("可以用"));
        assertEquals("可以用", last().getContent());
    }

    @Test
    public void voice_guest_needsLogin() throws IOException {
        repo.guest = true;
        vm.init(ChatArgs.ofRecord(-1));
        List<Integer> events = new ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));
        File wav = temp.newFile("guest.wav");

        assertFalse(vm.onVoicePress());
        vm.recognizeSpeech(wav);

        assertEquals(Arrays.asList(ChatEvent.NEED_LOGIN, ChatEvent.NEED_LOGIN), events);
        assertTrue(repo.asrCalls.isEmpty());
        assertFalse(wav.exists());
    }

    @Test
    public void voice_recognizingState_andErrorToast() throws IOException {
        vm.init(ChatArgs.ofRecord(-1));
        SingleSubject<String> pending = SingleSubject.create();
        repo.asr = w -> pending;
        List<String> toasts = new ArrayList<>();
        vm.getToast().observeForever(toasts::add);
        File wav = temp.newFile("pending.wav");

        vm.recognizeSpeech(wav);
        assertEquals(Boolean.TRUE, vm.getVoiceRecognizing().getValue());
        assertFalse("识别中忽略新的按下", vm.onVoicePress());

        pending.onError(ApiException.of(ApiException.AI_UNAVAILABLE, AiConstants.TOAST_VOICE_UNAVAILABLE));
        assertEquals(Collections.singletonList(AiConstants.TOAST_VOICE_UNAVAILABLE), toasts);
        assertEquals(Boolean.FALSE, vm.getVoiceRecognizing().getValue());
        assertNull(vm.getVoiceText().getValue());
        assertFalse(wav.exists());
        assertTrue(vm.onVoicePress());

        vm.onVoiceTooShort();
        assertEquals(AiConstants.TOAST_VOICE_TOO_SHORT, toasts.get(toasts.size() - 1));
    }

    @Test
    public void voice_onCleared_cancelsUpload() throws IOException {
        vm.init(ChatArgs.ofRecord(-1));
        SingleSubject<String> pending = SingleSubject.create();
        repo.asr = w -> pending;
        File wav = temp.newFile("cleared.wav");
        vm.recognizeSpeech(wav);
        vm.onCleared();
        assertFalse(pending.hasObservers());
        assertFalse(wav.exists());
    }

    // ==================== 输入方式（文字 / 语音） ====================

    /** 记住语音后打开的新页面（Q02 每次打开都是新的 ViewModel） */
    private ChatViewModel openWithVoiceRemembered() {
        modeStore.setVoiceMode(true);
        ChatViewModel chat = new ChatViewModel(repo, modeStore);
        chat.getComposerButtons().observeForever(v -> { });
        return chat;
    }

    @Test
    public void inputMode_initial_isRemembered() {
        assertEquals("没有记录时为文字", Boolean.FALSE, vm.getVoiceMode().getValue());
        assertEquals(ComposerButtons.EMPTY, buttons());

        ChatViewModel chat = openWithVoiceRemembered();
        assertEquals("与 Q01 共用记住的方式", Boolean.TRUE, chat.getVoiceMode().getValue());
        ComposerButtons b = chat.getComposerButtons().getValue();
        assertTrue(b.isTalkVisible());
        assertTrue(b.isKeyboardVisible());
        assertFalse(b.isActionVisible());
    }

    @Test
    public void toggle_switchesAndRemembers_permissionTiming() {
        vm.init(ChatArgs.ofRecord(-1));
        assertTrue("没有权限：页面申请", vm.onToggleInputMode(false));
        assertEquals(Boolean.FALSE, vm.getVoiceMode().getValue());
        vm.onMicPermissionResult(true);
        assertEquals("授权后进入语音", Boolean.TRUE, vm.getVoiceMode().getValue());
        assertTrue(modeStore.isVoiceMode());
        assertTrue(buttons().isTalkVisible());

        assertFalse(vm.onToggleInputMode(true));
        assertEquals(Boolean.FALSE, vm.getVoiceMode().getValue());
        assertFalse(modeStore.isVoiceMode());
    }

    @Test
    public void toggle_guest_needsLogin_noChange() {
        repo.guest = true;
        vm.init(ChatArgs.ofRecord(-1));
        List<Integer> events = new ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));
        assertFalse(vm.onToggleInputMode(true));
        assertFalse(vm.onToggleInputMode(false));
        assertEquals(Arrays.asList(ChatEvent.NEED_LOGIN, ChatEvent.NEED_LOGIN), events);
        assertEquals(Boolean.FALSE, vm.getVoiceMode().getValue());
        assertFalse(modeStore.isVoiceMode());
    }

    @Test
    public void recognized_temporaryText_revertsToVoiceAfterSend() throws IOException {
        ChatViewModel chat = openWithVoiceRemembered();
        chat.init(ChatArgs.ofRecord(-1));
        repo.asrResult = Single.just("需要打什么药？");
        chat.getInputText().setValue("番茄晚疫病");

        chat.recognizeSpeech(temp.newFile("draft.wav"));
        assertEquals("追加到草稿之后", "番茄晚疫病需要打什么药？", chat.getInputText().getValue());
        assertEquals("临时切到文字", Boolean.FALSE, chat.getVoiceMode().getValue());
        assertTrue("不改变记住的方式", modeStore.isVoiceMode());

        chat.send();
        assertEquals(1, repo.sendCalls.size());
        assertEquals("番茄晚疫病需要打什么药？", repo.sendCalls.get(0).content);
        assertEquals("", chat.getInputText().getValue());
        assertEquals("发送后回到「按住 说话」", Boolean.TRUE, chat.getVoiceMode().getValue());
        assertTrue(chat.getComposerButtons().getValue().isTalkVisible());
    }

    @Test
    public void voiceMode_whileStreaming_stopShown_recognizedStaysUnsent() throws IOException {
        ChatViewModel chat = openWithVoiceRemembered();
        PublishProcessor<StreamChunk> answer = PublishProcessor.create();
        repo.answer = call -> answer;
        chat.init(ChatArgs.ofQuestion(QUESTION));
        ComposerButtons b = chat.getComposerButtons().getValue();
        assertTrue(b.isVoiceMode());
        assertTrue("生成中为停止按钮", b.isStop());
        assertTrue(b.isActionVisible());

        repo.asrResult = Single.just("那需要打什么药？");
        chat.recognizeSpeech(temp.newFile("streaming.wav"));
        assertEquals("那需要打什么药？", chat.getInputText().getValue());
        assertEquals(Boolean.FALSE, chat.getVoiceMode().getValue());
        assertEquals("只填文字，不发送", 1, repo.sendCalls.size());
        assertTrue(chat.getComposerButtons().getValue().isStop());
    }

    @Test
    public void voiceMode_sendsHiddenDraft() {
        ChatViewModel chat = openWithVoiceRemembered();
        chat.init(ChatArgs.ofRecord(-1));
        chat.getInputText().setValue("刚才打的字");
        assertTrue("隐藏的草稿：显示发送", chat.getComposerButtons().getValue().canSend());
        chat.onActionClick();
        assertEquals(1, repo.sendCalls.size());
        assertEquals("刚才打的字", repo.sendCalls.get(0).content);
        assertEquals(Boolean.TRUE, chat.getVoiceMode().getValue());
    }
}
