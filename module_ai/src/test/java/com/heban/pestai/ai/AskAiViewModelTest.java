package com.heban.pestai.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.ai.compose.ComposerButtons;
import com.heban.pestai.ai.compose.InputModeStore;
import com.heban.pestai.ai.compose.PendingImage;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;

public class AskAiViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxSchedulersRule rxRule = new RxSchedulersRule();
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private FakeAiRepository repo;
    /** 记住的输入方式（Q01 / Q02 共用同一个文件） */
    private InputModeStore modeStore;

    @Before
    public void setUp() {
        repo = new FakeAiRepository();
        modeStore = new InputModeStore(new FakeSharedPreferences());
    }

    private AskAiViewModel create() {
        AskAiViewModel vm = new AskAiViewModel(repo, modeStore);
        // MediatorLiveData 只有在被观察时才会响应数据源变化
        vm.getComposerButtons().observeForever(v -> { });
        return vm;
    }

    private static ComposerButtons buttons(AskAiViewModel vm) {
        return vm.getComposerButtons().getValue();
    }

    private static List<Integer> collect(AskAiViewModel vm) {
        List<Integer> events = new ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));
        return events;
    }

    // ==================== 游客 ====================

    @Test
    public void guest_actionsNeedLogin() {
        repo.guest = true;
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);

        vm.onInputClick();
        vm.onCameraClick();
        vm.onAlbumClick();
        vm.getInputText().setValue("番茄叶片发黄");
        vm.onActionClick();
        vm.onHistoryClick();

        int login = AskAiViewModel.Event.NEED_LOGIN;
        assertEquals(Arrays.asList(login, login, login, login, login), events);
        vm.onImagesPicked(Collections.singletonList("content://picked/1"));
        assertTrue("游客不加入图片", repo.uploadCalls.isEmpty());
        assertTrue(repo.sendCalls.isEmpty());
    }

    @Test
    public void onResume_updatesGuestState() {
        repo.guest = true;
        AskAiViewModel vm = create();
        assertTrue(vm.isGuest());

        repo.guest = false;
        vm.onResume();
        assertFalse("登录后回来可以输入", vm.isGuest());
        assertEquals(Boolean.FALSE, vm.getGuest().getValue());

        vm.onInputClick();
        assertNull("登录用户点输入框不弹登录", vm.getEvent().getValue());
    }

    // ==================== 输入栏按钮 ====================

    @Test
    public void composerButtons_followTextAndImages() {
        repo.upload = FakeAiRepository::urlSingle;
        AskAiViewModel vm = create();
        assertEquals("初始：相机与「+」，没有发送按钮", ComposerButtons.EMPTY, buttons(vm));
        assertTrue(buttons(vm).isCameraVisible());
        assertTrue(buttons(vm).isAlbumVisible());
        assertFalse(buttons(vm).isActionVisible());

        vm.getInputText().setValue("   ");
        assertEquals("只有空白按没有文字处理", ComposerButtons.EMPTY, buttons(vm));

        vm.getInputText().setValue("番茄");
        assertFalse("有文字：只显示发送", buttons(vm).isCameraVisible());
        assertFalse(buttons(vm).isAlbumVisible());
        assertTrue(buttons(vm).isActionVisible());
        assertTrue(buttons(vm).canSend());
        assertFalse("Q01 没有停止状态", buttons(vm).isStop());

        vm.getInputText().setValue("");
        vm.onImagesPicked(Collections.singletonList("content://picked/1"));
        assertTrue("没有文字、有图片：三个按钮都显示", buttons(vm).isCameraVisible());
        assertTrue(buttons(vm).isAlbumVisible());
        assertTrue(buttons(vm).isActionVisible());
        assertTrue(buttons(vm).canSend());

        vm.getInputText().setValue("看看这张");
        assertFalse(buttons(vm).isCameraVisible());
        assertTrue(buttons(vm).isActionVisible());

        vm.send();
        assertEquals("发送后清空，恢复初始按钮", ComposerButtons.EMPTY, buttons(vm));
    }

    @Test
    public void fullQueue_cameraAndAlbumDimmed_andToast() {
        repo.upload = FakeAiRepository::urlSingle;
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        List<String> toasts = new ArrayList<>();
        vm.getToast().observeForever(toasts::add);
        vm.onImagesPicked(Arrays.asList("c://1", "c://2", "c://3", "c://4", "c://5", "c://6", "c://7", "c://8",
                "c://9", "c://10"));
        assertEquals("超出的丢弃并提示", Collections.singletonList(AiConstants.TOAST_TOO_MANY_IMAGES), toasts);
        assertEquals(9, vm.getImages().getValue().size());
        assertTrue(buttons(vm).isAddDimmed());
        assertTrue("变淡但仍显示", buttons(vm).isCameraVisible());
        assertEquals(0, vm.remainingImages());
        events.clear();
        toasts.clear();

        vm.onCameraClick();
        vm.onAlbumClick();
        assertEquals(Arrays.asList(AiConstants.TOAST_TOO_MANY_IMAGES, AiConstants.TOAST_TOO_MANY_IMAGES), toasts);
        assertTrue("已满时不打开相机 / 相册", events.isEmpty());

        vm.removeImage(vm.getImages().getValue().get(0).getId());
        assertFalse(buttons(vm).isAddDimmed());
    }

    // ==================== 拍照与相册 ====================

    @Test
    public void camera_takesPhotoDirectly_thenFocusesInput() {
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        vm.onCameraClick();
        assertEquals("不再弹出来源面板", Collections.singletonList(AskAiViewModel.Event.TAKE_PHOTO), events);

        vm.onImagesPicked(Collections.singletonList("content://com.heban.pestai.ai.fileprovider/ai_camera/IMG_1.jpg"));
        assertEquals("content://com.heban.pestai.ai.fileprovider/ai_camera/IMG_1.jpg",
                vm.getImages().getValue().get(0).getLocalUri());
        assertEquals(Integer.valueOf(1), vm.getImageCount().getValue());
        assertEquals("选好照片后输入框获得焦点", AskAiViewModel.Event.FOCUS_INPUT, events.get(events.size() - 1).intValue());
        assertTrue(buttons(vm).canSend());
    }

    @Test
    public void album_opensPickerWithRemainingCount() {
        repo.upload = FakeAiRepository::urlSingle;
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        vm.onAlbumClick();
        assertEquals(Collections.singletonList(AskAiViewModel.Event.PICK_IMAGE), events);
        assertEquals(AiConstants.MAX_IMAGES, vm.remainingImages());

        vm.onImagesPicked(Arrays.asList("content://picked/1", "content://picked/2"));
        assertEquals(7, vm.remainingImages());
    }

    // ==================== 发送 ====================

    @Test
    public void send_opensChatWithUploadedImages_andClearsInput() {
        repo.upload = FakeAiRepository::urlSingle;
        AskAiViewModel vm = create();
        assertFalse(buttons(vm).canSend());
        vm.send();
        assertNull("空输入不发送", vm.getEvent().getValue());

        vm.getInputText().setValue("  番茄叶片发黄卷曲  ");
        vm.onImagesPicked(Arrays.asList("content://picked/1", "content://picked/2"));
        assertEquals("选好即上传", Arrays.asList("content://picked/1", "content://picked/2"), repo.uploadCalls);
        assertTrue(buttons(vm).canSend());
        vm.onActionClick();

        AskAiViewModel.Event event = vm.getEvent().getValue();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, event.type);
        assertEquals("番茄叶片发黄卷曲", event.args.question);
        assertEquals(Arrays.asList("content://picked/1", "content://picked/2"), event.args.imageUris);
        assertEquals(Arrays.asList(FakeAiRepository.urlOf("content://picked/1"),
                FakeAiRepository.urlOf("content://picked/2")), event.args.imageUrls);
        assertTrue(event.args.hasImages());
        assertEquals("", vm.getInputText().getValue());
        assertTrue(vm.getImages().getValue().isEmpty());
        assertEquals(Integer.valueOf(0), vm.getImageCount().getValue());
        assertFalse(buttons(vm).canSend());
        assertEquals("Q02 不再上传", 2, repo.uploadCalls.size());
    }

    @Test
    public void imageOnly_canSend_andOpensChatWithEmptyQuestion() {
        AskAiViewModel vm = create();
        vm.onImagesPicked(Collections.singletonList("content://picked/2"));
        assertTrue(buttons(vm).canSend());
        assertEquals(8, vm.remainingImages());
        long id = vm.getImages().getValue().get(0).getId();
        vm.removeImage(id);
        assertFalse(buttons(vm).canSend());
        assertFalse(buttons(vm).isActionVisible());

        vm.onImagesPicked(Collections.singletonList("content://picked/3"));
        vm.send();
        AskAiViewModel.Event event = vm.getEvent().getValue();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, event.type);
        assertEquals("", event.args.question);
        assertEquals(Collections.singletonList("content://picked/3"), event.args.imageUris);
        assertEquals(1, event.args.imageUrls.size());
    }

    @Test
    public void send_blockedWhileUploading() {
        SingleSubject<String> upload = SingleSubject.create();
        repo.upload = uri -> upload;
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        vm.getInputText().setValue("看看这张");
        vm.onImagesPicked(Collections.singletonList("content://picked/1"));
        assertTrue("上传中也可以点发送", buttons(vm).canSend());

        vm.send();
        assertEquals(AiConstants.TOAST_IMAGES_UPLOADING, vm.getToast().getValue());
        assertFalse(events.contains(AskAiViewModel.Event.OPEN_CHAT));
        assertEquals("输入保留", "看看这张", vm.getInputText().getValue());
        assertEquals(1, vm.getImages().getValue().size());

        upload.onSuccess("http://x/a.jpg");
        vm.send();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, events.get(events.size() - 1).intValue());
        assertEquals(Collections.singletonList("http://x/a.jpg"), vm.getEvent().getValue().args.imageUrls);
    }

    @Test
    public void send_blockedWithFailedImage_untilRetriedOrRemoved() {
        repo.uploadResult = Single.error(ApiException.of(ApiException.NETWORK));
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        vm.onImagesPicked(Collections.singletonList("content://picked/1"));
        assertEquals(AiConstants.TOAST_IMAGE_UPLOAD_FAILED, vm.getToast().getValue());
        PendingImage failed = vm.getImages().getValue().get(0);
        assertTrue(failed.isFailed());

        vm.send();
        assertEquals(AiConstants.TOAST_IMAGES_FAILED, vm.getToast().getValue());
        assertFalse(events.contains(AskAiViewModel.Event.OPEN_CHAT));

        repo.uploadResult = Single.just("http://x/a.jpg");
        vm.retryImage(failed.getId());
        assertTrue(vm.getImages().getValue().get(0).isDone());
        vm.send();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, events.get(events.size() - 1).intValue());
    }

    // ==================== 顶部栏 ====================

    @Test
    public void historyButton_opensHistory() {
        AskAiViewModel vm = create();
        vm.onHistoryClick();
        assertEquals(AskAiViewModel.Event.OPEN_HISTORY, vm.getEvent().getValue().type);
    }

    // ==================== 语音输入 ====================

    private File wav() throws IOException {
        return temp.newFile();
    }

    private static List<String> toasts(AskAiViewModel vm) {
        List<String> toasts = new ArrayList<>();
        vm.getToast().observeForever(toasts::add);
        return toasts;
    }

    @Test
    public void voice_success_appendsToInput_deletesFile_doesNotSend() throws IOException {
        repo.asrResult = Single.just("  番茄叶子发黄怎么办 ");
        modeStore.setVoiceMode(true);
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        List<String> toasts = toasts(vm);
        List<String> texts = new ArrayList<>();
        vm.getVoiceText().observeForever(texts::add);
        File file = wav();

        assertTrue("登录用户可以开始录音", vm.onVoicePress());
        vm.recognizeSpeech(file);

        assertEquals(Collections.singletonList(file), repo.asrCalls);
        assertEquals("识别结果去掉首尾空白，页面据此让输入框获得焦点", Collections.singletonList("番茄叶子发黄怎么办"), texts);
        assertEquals(Boolean.FALSE, vm.getVoiceRecognizing().getValue());
        assertFalse("识别完成后删除录音", file.exists());
        assertTrue(toasts.isEmpty());
        assertTrue("不自动发送", events.isEmpty());
        assertEquals("文字填入输入框", "番茄叶子发黄怎么办", vm.getInputText().getValue());
        assertEquals("临时切到文字，方便修改", Boolean.FALSE, vm.getVoiceMode().getValue());
        assertTrue("有文字：只显示发送", buttons(vm).canSend());
        assertFalse(buttons(vm).isCameraVisible());
    }

    @Test
    public void voice_whileRecognizing_pressIgnored_extraRecordingDeleted() throws IOException {
        SingleSubject<String> pending = SingleSubject.create();
        repo.asr = w -> pending;
        AskAiViewModel vm = create();
        List<String> texts = new ArrayList<>();
        vm.getVoiceText().observeForever(texts::add);
        File first = wav();
        File second = wav();

        vm.recognizeSpeech(first);
        assertEquals(Boolean.TRUE, vm.getVoiceRecognizing().getValue());
        assertTrue("上传中保留文件", first.exists());
        assertFalse("识别中忽略新的按下", vm.onVoicePress());
        assertNull("忽略时不弹任何事件", vm.getEvent().getValue());

        vm.recognizeSpeech(second);
        assertEquals("识别中只识别一段", 1, repo.asrCalls.size());
        assertFalse(second.exists());

        pending.onSuccess("黄瓜叶片有白粉");
        assertEquals(Collections.singletonList("黄瓜叶片有白粉"), texts);
        assertEquals(Boolean.FALSE, vm.getVoiceRecognizing().getValue());
        assertFalse(first.exists());
        assertTrue("识别结束后可以再次录音", vm.onVoicePress());
    }

    @Test
    public void voice_emptyText_toastsNotHeard() throws IOException {
        repo.asrResult = Single.just("   ");
        AskAiViewModel vm = create();
        List<String> toasts = toasts(vm);
        File file = wav();
        vm.recognizeSpeech(file);
        assertEquals(Collections.singletonList(AiConstants.TOAST_VOICE_NOT_HEARD), toasts);
        assertNull("没有文字不插入", vm.getVoiceText().getValue());
        assertFalse(file.exists());
        assertEquals(Boolean.FALSE, vm.getVoiceRecognizing().getValue());
    }

    @Test
    public void voice_errors_toastApiExceptionMessage_andDeleteFile() throws IOException {
        Object[][] cases = {
                {ApiException.of(ApiException.PARAM_ERROR, "没有听清，请再说一遍"), "没有听清，请再说一遍"},
                {ApiException.of(ApiException.PARAM_ERROR), AiConstants.TOAST_VOICE_NOT_HEARD},
                {ApiException.of(ApiException.AI_UNAVAILABLE, "语音识别暂不可用，请稍后再试"),
                        AiConstants.TOAST_VOICE_UNAVAILABLE},
                {ApiException.of(ApiException.AI_UNAVAILABLE), AiConstants.TOAST_VOICE_UNAVAILABLE},
                {ApiException.of(ApiException.TOO_FREQUENT), "操作太频繁，请稍后再试"},
                {ApiException.of(ApiException.NETWORK), "网络连接失败，请检查网络后重试"},
                {new UnknownHostException("10.0.2.2"), "网络连接失败，请检查网络后重试"},
                {ApiException.of(ApiException.SERVER_ERROR, "服务器开小差了"), "服务器开小差了"},
        };
        for (Object[] c : cases) {
            repo.asrResult = Single.error((Throwable) c[0]);
            AskAiViewModel vm = create();
            List<String> toasts = toasts(vm);
            File file = wav();
            vm.recognizeSpeech(file);
            assertEquals(String.valueOf(c[0]), Collections.singletonList((String) c[1]), toasts);
            assertNull(vm.getVoiceText().getValue());
            assertFalse("失败也删除录音", file.exists());
            assertEquals(Boolean.FALSE, vm.getVoiceRecognizing().getValue());
            assertTrue("失败后可以重新录音", vm.onVoicePress());
        }
    }

    @Test
    public void voice_guest_needsLogin_andDeletesRecording() throws IOException {
        repo.guest = true;
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        File file = wav();

        assertFalse("游客按下麦克风不录音", vm.onVoicePress());
        vm.recognizeSpeech(file);

        int login = AskAiViewModel.Event.NEED_LOGIN;
        assertEquals(Arrays.asList(login, login), events);
        assertTrue("游客不上传", repo.asrCalls.isEmpty());
        assertFalse(file.exists());
    }

    @Test
    public void voice_tooShortAndMicUnavailable_toast() {
        AskAiViewModel vm = create();
        List<String> toasts = toasts(vm);
        vm.onVoiceTooShort();
        vm.onVoiceUnavailable();
        assertEquals(Arrays.asList(AiConstants.TOAST_VOICE_TOO_SHORT, AiConstants.TOAST_MIC_UNAVAILABLE), toasts);
        assertTrue(repo.asrCalls.isEmpty());
    }

    @Test
    public void voice_onCleared_cancelsUploadAndDeletesFile() throws IOException {
        SingleSubject<String> pending = SingleSubject.create();
        repo.asr = w -> pending;
        AskAiViewModel vm = create();
        File file = wav();
        vm.recognizeSpeech(file);
        assertTrue(pending.hasObservers());

        vm.onCleared();
        assertFalse("页面关闭取消上传", pending.hasObservers());
        assertFalse("取消也删除录音", file.exists());

        File late = wav();
        vm.recognizeSpeech(late);
        assertEquals("释放后交来的录音直接删除", 1, repo.asrCalls.size());
        assertFalse(late.exists());
    }

    // ==================== 输入方式（文字 / 语音） ====================

    @Test
    public void inputMode_initial_isRemembered() {
        AskAiViewModel text = create();
        assertEquals("没有记录时为文字", Boolean.FALSE, text.getVoiceMode().getValue());
        assertEquals(ComposerButtons.EMPTY, buttons(text));

        modeStore.setVoiceMode(true);
        AskAiViewModel voice = create();
        assertEquals("打开页面时为记住的语音", Boolean.TRUE, voice.getVoiceMode().getValue());
        assertTrue(buttons(voice).isVoiceMode());
        assertTrue(buttons(voice).isTalkVisible());
        assertTrue(buttons(voice).isKeyboardVisible());
        assertFalse(buttons(voice).isActionVisible());
    }

    @Test
    public void toggle_withPermission_switchesAndRemembers_draftKept() {
        AskAiViewModel vm = create();
        vm.getInputText().setValue("番茄叶片");
        assertFalse(buttons(vm).isMicVisible());

        assertFalse("有权限不需要申请", vm.onToggleInputMode(true));
        assertEquals(Boolean.TRUE, vm.getVoiceMode().getValue());
        assertTrue("记住，Q02 也使用", modeStore.isVoiceMode());
        assertEquals("草稿保留", "番茄叶片", vm.getInputText().getValue());
        assertTrue(buttons(vm).isTalkVisible());
        assertFalse("语音：只有「按住 说话」与键盘，不显示发送", buttons(vm).isActionVisible());
        assertFalse(buttons(vm).isCameraVisible());
        assertFalse(buttons(vm).isAlbumVisible());
        assertTrue(buttons(vm).isKeyboardVisible());

        assertFalse(vm.onToggleInputMode(false));
        assertEquals("点键盘切回文字", Boolean.FALSE, vm.getVoiceMode().getValue());
        assertFalse(modeStore.isVoiceMode());
        assertEquals("番茄叶片", vm.getInputText().getValue());
        assertFalse("有文字：只显示发送", buttons(vm).isCameraVisible());
    }

    @Test
    public void toggle_withoutPermission_requests_thenGrantedOrDenied() {
        AskAiViewModel vm = create();
        List<String> toasts = toasts(vm);
        assertTrue("没有权限：页面申请", vm.onToggleInputMode(false));
        assertEquals("授权前留在文字", Boolean.FALSE, vm.getVoiceMode().getValue());

        vm.onMicPermissionResult(false);
        assertEquals(Boolean.FALSE, vm.getVoiceMode().getValue());
        assertEquals(Collections.singletonList(AiConstants.TOAST_MIC_DENIED), toasts);

        assertTrue(vm.onToggleInputMode(false));
        vm.onMicPermissionResult(true);
        assertEquals("授权后进入语音", Boolean.TRUE, vm.getVoiceMode().getValue());
        assertTrue(modeStore.isVoiceMode());
        assertEquals(1, toasts.size());
    }

    @Test
    public void permissionFromHoldToTalk_staysVoice_toasts() {
        modeStore.setVoiceMode(true);
        AskAiViewModel vm = create();
        List<String> toasts = toasts(vm);
        // 记住语音、权限被撤销：仍显示语音，按住时申请
        vm.onMicPermissionResult(true);
        assertEquals(Boolean.TRUE, vm.getVoiceMode().getValue());
        assertEquals(Collections.singletonList(AiConstants.TOAST_MIC_GRANTED), toasts);
    }

    @Test
    public void toggle_guest_needsLogin_noChange() {
        repo.guest = true;
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        assertFalse("游客不申请权限", vm.onToggleInputMode(false));
        assertFalse(vm.onToggleInputMode(true));
        assertEquals(Arrays.asList(AskAiViewModel.Event.NEED_LOGIN, AskAiViewModel.Event.NEED_LOGIN), events);
        assertEquals(Boolean.FALSE, vm.getVoiceMode().getValue());
        assertFalse(modeStore.isVoiceMode());

        modeStore.setVoiceMode(true);
        AskAiViewModel voice = create();
        List<Integer> voiceEvents = collect(voice);
        assertFalse(voice.onToggleInputMode(true));
        assertEquals("游客在语音输入时点键盘也弹出登录", Collections.singletonList(AskAiViewModel.Event.NEED_LOGIN),
                voiceEvents);
        assertEquals(Boolean.TRUE, voice.getVoiceMode().getValue());
    }

    @Test
    public void recognized_appendsToDraft_temporaryText_revertsAfterSend() throws IOException {
        modeStore.setVoiceMode(true);
        repo.asrResult = Single.just("叶子发黄怎么办");
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        vm.getInputText().setValue("番茄");

        vm.recognizeSpeech(wav());
        assertEquals("追加到草稿之后", "番茄叶子发黄怎么办", vm.getInputText().getValue());
        assertEquals("临时切到文字", Boolean.FALSE, vm.getVoiceMode().getValue());
        assertTrue("不改变记住的方式", modeStore.isVoiceMode());
        assertFalse(buttons(vm).isVoiceMode());
        assertTrue(buttons(vm).canSend());

        vm.onResume();
        assertEquals("回到页面时临时文字不变", Boolean.FALSE, vm.getVoiceMode().getValue());

        vm.send();
        assertEquals(Collections.singletonList(AskAiViewModel.Event.OPEN_CHAT), events);
        assertEquals("", vm.getInputText().getValue());
        assertEquals("发送后回到记住的语音", Boolean.TRUE, vm.getVoiceMode().getValue());
        assertTrue(buttons(vm).isTalkVisible());
        assertFalse(buttons(vm).isActionVisible());
    }

    @Test
    public void recognized_blankDraft_replaced_textModeStaysText() throws IOException {
        repo.asrResult = Single.just("黄瓜白粉病");
        AskAiViewModel vm = create();
        vm.getInputText().setValue("  ");
        vm.recognizeSpeech(wav());
        assertEquals("只有空白的草稿不保留", "黄瓜白粉病", vm.getInputText().getValue());
        assertEquals(Boolean.FALSE, vm.getVoiceMode().getValue());
        vm.send();
        assertEquals("记住的是文字：发送后仍是文字", Boolean.FALSE, vm.getVoiceMode().getValue());
    }

    @Test
    public void recognizeFailed_staysVoice() throws IOException {
        modeStore.setVoiceMode(true);
        repo.asrResult = Single.error(ApiException.of(ApiException.NETWORK));
        AskAiViewModel vm = create();
        vm.recognizeSpeech(wav());
        assertEquals("失败留在语音", Boolean.TRUE, vm.getVoiceMode().getValue());
        assertEquals("", vm.getInputText().getValue());
    }

    @Test
    public void voiceMode_sendsHiddenDraftAndImages() {
        repo.upload = FakeAiRepository::urlSingle;
        modeStore.setVoiceMode(true);
        AskAiViewModel vm = create();
        List<AskAiViewModel.Event> opened = new ArrayList<>();
        vm.getEvent().observeForever(e -> {
            if (e.type == AskAiViewModel.Event.OPEN_CHAT) {
                opened.add(e);
            }
        });
        vm.getInputText().setValue("看看这张");
        vm.onImagesPicked(Collections.singletonList("content://picked/1"));
        assertTrue(buttons(vm).isVoiceMode());
        assertTrue(buttons(vm).canSend());

        vm.onActionClick();
        assertEquals(1, opened.size());
        assertEquals("看看这张", opened.get(0).args.question);
        assertEquals(1, opened.get(0).args.imageUrls.size());
        assertEquals(Boolean.TRUE, vm.getVoiceMode().getValue());
        assertFalse(buttons(vm).isActionVisible());
    }

    @Test
    public void onResume_syncsModeChangedInChat() {
        AskAiViewModel vm = create();
        // Q02 中切到语音（同一个文件）
        modeStore.setVoiceMode(true);
        assertEquals(Boolean.FALSE, vm.getVoiceMode().getValue());
        vm.onResume();
        assertEquals("回到 Q01 时同步", Boolean.TRUE, vm.getVoiceMode().getValue());
    }

    @Test
    public void rotation_keepsCurrentMode() {
        AskAiViewModel vm = create();
        vm.onToggleInputMode(true);
        // 旋转屏幕：同一个 ViewModel，页面重新观察
        List<Boolean> modes = new ArrayList<>();
        vm.getVoiceMode().observeForever(modes::add);
        assertEquals(Collections.singletonList(true), modes);
    }
}
