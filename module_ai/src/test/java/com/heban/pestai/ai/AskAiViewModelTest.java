package com.heban.pestai.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SuggestionVO;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

public class AskAiViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxSchedulersRule rxRule = new RxSchedulersRule();

    private FakeAiRepository repo;

    @Before
    public void setUp() {
        repo = new FakeAiRepository();
    }

    private AskAiViewModel create() {
        AskAiViewModel vm = new AskAiViewModel(repo);
        vm.getCanSend().observeForever(v -> { });
        return vm;
    }

    private static RecordItemVO cucumber(String status) {
        return FakeAiRepository.record(2036, status, "黄瓜霜霉病", 0.92, "中度");
    }

    // ==================== 游客 ====================

    @Test
    public void guest_usesBuiltInSuggestions_withoutRequests() {
        repo.guest = true;
        AskAiViewModel vm = create();
        vm.loadSuggestions();
        vm.refreshLatest();

        assertEquals(AiConstants.defaultSuggestions(), vm.getSuggestions().getValue());
        assertEquals(UiState.SUCCESS, vm.getSuggestionState().getValue());
        assertEquals(0, repo.suggestionsCalls);
        assertEquals(0, repo.recentCalls);
        assertNull(vm.getLatestRecord().getValue());
    }

    @Test
    public void guest_actionsNeedLogin() {
        repo.guest = true;
        AskAiViewModel vm = create();
        List<Integer> events = new ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));

        vm.onInputClick();
        vm.onPickImageClick();
        vm.askSuggestion(AiConstants.defaultSuggestions().get(0));
        vm.continueAsk();
        vm.getInputText().setValue("番茄叶片发黄");
        vm.send();
        vm.onHistoryClick();
        vm.onTextCardClick();
        vm.onImageCardClick();
        vm.onTakePhoto();
        vm.onFollowCardClick();
        vm.onTemplateClick("白菜软腐病怎么预防？");

        int login = AskAiViewModel.Event.NEED_LOGIN;
        assertEquals(Arrays.asList(login, login, login, login, login, login, login, login, login, login, login),
                events);
        assertEquals(Boolean.FALSE, vm.getTemplatesVisible().getValue());
        assertEquals("游客不加载识别记录", 0, repo.recentCalls);
    }

    // ==================== 大家常问 ====================

    @Test
    public void suggestions_atMostThree_andLoadedOnce() {
        repo.suggestionsResult = Single.just(Arrays.asList(
                FakeAiRepository.suggestion(1, "番茄叶片发黄卷曲是什么原因？"),
                FakeAiRepository.suggestion(2, "黄瓜叶子发黄卷边是什么原因？"),
                FakeAiRepository.suggestion(3, "小麦播种前怎样拌种防病？"),
                FakeAiRepository.suggestion(4, "第四条")));
        AskAiViewModel vm = create();
        vm.loadSuggestions();
        vm.loadSuggestions();

        List<SuggestionVO> list = vm.getSuggestions().getValue();
        assertEquals(3, list.size());
        assertEquals("小麦播种前怎样拌种防病？", list.get(2).getText());
        assertEquals(UiState.SUCCESS, vm.getSuggestionState().getValue());
        assertEquals(1, repo.suggestionsCalls);
    }

    @Test
    public void suggestions_errorFallsBackToBuiltIn() {
        repo.suggestionsResult = Single.error(ApiException.of(ApiException.NETWORK));
        AskAiViewModel vm = create();
        vm.loadSuggestions();
        assertEquals(AiConstants.defaultSuggestions(), vm.getSuggestions().getValue());
        assertEquals(UiState.SUCCESS, vm.getSuggestionState().getValue());
        assertNull("加载失败不提示", vm.getToast().getValue());
    }

    @Test
    public void suggestions_emptyHidesSection() {
        repo.suggestionsResult = Single.just(Collections.emptyList());
        AskAiViewModel vm = create();
        vm.loadSuggestions();
        assertEquals(UiState.EMPTY, vm.getSuggestionState().getValue());
        assertTrue(vm.getSuggestions().getValue().isEmpty());
    }

    @Test
    public void suggestionClick_opensChatWithQuestion() {
        AskAiViewModel vm = create();
        vm.askSuggestion(FakeAiRepository.suggestion(1, "白粉病和霜霉病怎么区分？"));
        AskAiViewModel.Event event = vm.getEvent().getValue();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, event.type);
        assertEquals("白粉病和霜霉病怎么区分？", event.args.question);
        assertEquals(RouterPath.NO_RECORD, event.args.recordId);
        assertNull(event.args.imageUri);
    }

    // ==================== 最近识别 ====================

    @Test
    public void latest_successRecordShown() {
        repo.recentResult = Single.just(Collections.singletonList(cucumber("SUCCESS")));
        AskAiViewModel vm = create();
        vm.onResume();

        assertEquals(2036, vm.getLatestRecord().getValue().getId());
        assertEquals("黄瓜霜霉病 · 匹配度 92%", vm.getLatestTitle().getValue());
        assertTrue(vm.getLatestLabel().getValue().startsWith("最近识别"));

        vm.continueAsk();
        AskAiViewModel.Event event = vm.getEvent().getValue();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, event.type);
        assertEquals(2036, event.args.recordId);
        assertNull(event.args.question);
    }

    @Test
    public void latest_failedRecordOrErrorHidden() {
        repo.recentResult = Single.just(Collections.singletonList(cucumber("FAILED")));
        AskAiViewModel vm = create();
        vm.refreshLatest();
        assertNull(vm.getLatestRecord().getValue());

        repo.recentResult = Single.just(Collections.singletonList(cucumber("SUCCESS")));
        vm.refreshLatest();
        assertEquals(2036, vm.getLatestRecord().getValue().getId());

        repo.recentResult = Single.error(new IllegalStateException("IRecordService 未注册"));
        vm.refreshLatest();
        assertNull(vm.getLatestRecord().getValue());
        assertEquals("", vm.getLatestTitle().getValue());
    }

    // ==================== 输入与发送 ====================

    @Test
    public void send_opensChat_andClearsInput() {
        AskAiViewModel vm = create();
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
        vm.send();
        assertNull("空输入不发送", vm.getEvent().getValue());

        vm.getInputText().setValue("  番茄叶片发黄卷曲  ");
        vm.setPendingImage("content://picked/1");
        assertEquals(Boolean.TRUE, vm.getCanSend().getValue());
        vm.send();

        AskAiViewModel.Event event = vm.getEvent().getValue();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, event.type);
        assertEquals("番茄叶片发黄卷曲", event.args.question);
        assertEquals("content://picked/1", event.args.imageUri);
        assertEquals("", vm.getInputText().getValue());
        assertNull(vm.getPendingImage().getValue());
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
    }

    @Test
    public void imageOnly_canSend() {
        AskAiViewModel vm = create();
        vm.onPickImageClick();
        assertEquals(AskAiViewModel.Event.PICK_IMAGE, vm.getEvent().getValue().type);
        vm.setPendingImage("content://picked/2");
        assertEquals(Boolean.TRUE, vm.getCanSend().getValue());
        vm.clearPendingImage();
        assertEquals(Boolean.FALSE, vm.getCanSend().getValue());
    }

    // ==================== 顶部栏与能力卡片 ====================

    private static List<Integer> collect(AskAiViewModel vm) {
        List<Integer> events = new ArrayList<>();
        vm.getEvent().observeForever(e -> events.add(e.type));
        return events;
    }

    @Test
    public void historyButton_opensHistory() {
        AskAiViewModel vm = create();
        vm.onHistoryClick();
        assertEquals(AskAiViewModel.Event.OPEN_HISTORY, vm.getEvent().getValue().type);
    }

    @Test
    public void textCard_focusesInput_andShowsTemplates() {
        AskAiViewModel vm = create();
        vm.getTemplatesVisible().observeForever(v -> { });
        List<Integer> events = collect(vm);
        assertEquals(Boolean.FALSE, vm.getTemplatesVisible().getValue());
        assertEquals(5, vm.getTemplates().size());
        assertEquals("番茄叶片出现黄褐色斑点，是什么病？", vm.getTemplates().get(0));
        assertEquals("果树开花期能打药吗？", vm.getTemplates().get(4));

        vm.onTextCardClick();
        assertEquals(Boolean.TRUE, vm.getTemplatesVisible().getValue());
        assertEquals(Collections.singletonList(AskAiViewModel.Event.FOCUS_INPUT), events);

        // 点模板：填入输入框、不发送，模板行保持显示，光标移到末尾（FOCUS_INPUT）
        vm.onTemplateClick("黄瓜苗期叶子发黄怎么办？");
        assertEquals("黄瓜苗期叶子发黄怎么办？", vm.getInputText().getValue());
        assertEquals(Boolean.TRUE, vm.getTemplatesVisible().getValue());
        assertEquals(Arrays.asList(AskAiViewModel.Event.FOCUS_INPUT, AskAiViewModel.Event.FOCUS_INPUT), events);
        assertEquals(Boolean.TRUE, vm.getCanSend().getValue());
    }

    @Test
    public void templates_hiddenAfterSend() {
        AskAiViewModel vm = create();
        vm.getTemplatesVisible().observeForever(v -> { });
        vm.onTextCardClick();
        vm.onTemplateClick("蚜虫用什么药防治效果好？");
        vm.send();
        assertEquals(Boolean.FALSE, vm.getTemplatesVisible().getValue());
        AskAiViewModel.Event event = vm.getEvent().getValue();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, event.type);
        assertEquals("蚜虫用什么药防治效果好？", event.args.question);
    }

    @Test
    public void templates_hiddenAfterClearingInput_orKeyboardHidden() {
        AskAiViewModel vm = create();
        vm.getTemplatesVisible().observeForever(v -> { });
        vm.onTextCardClick();
        vm.getInputText().setValue("番茄");
        assertEquals(Boolean.TRUE, vm.getTemplatesVisible().getValue());
        vm.getInputText().setValue("");
        assertEquals("清空输入后隐藏", Boolean.FALSE, vm.getTemplatesVisible().getValue());

        vm.onTextCardClick();
        assertEquals("输入框本来就是空的：仍然显示", Boolean.TRUE, vm.getTemplatesVisible().getValue());
        vm.onKeyboardHidden();
        assertEquals(Boolean.FALSE, vm.getTemplatesVisible().getValue());
    }

    @Test
    public void imageCard_showsSource_cameraAndAlbum() {
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);
        vm.onImageCardClick();
        vm.onTakePhoto();
        vm.onPickImageClick();
        assertEquals(Arrays.asList(AskAiViewModel.Event.SHOW_IMAGE_SOURCE, AskAiViewModel.Event.TAKE_PHOTO,
                AskAiViewModel.Event.PICK_IMAGE), events);

        vm.onImagePicked("content://com.heban.pestai.ai.fileprovider/ai_camera/IMG_1.jpg");
        assertEquals("content://com.heban.pestai.ai.fileprovider/ai_camera/IMG_1.jpg",
                vm.getPendingImage().getValue());
        assertEquals("选好照片后输入框获得焦点", AskAiViewModel.Event.FOCUS_INPUT, events.get(events.size() - 1).intValue());
        assertEquals(Boolean.TRUE, vm.getCanSend().getValue());
    }

    @Test
    public void followCard_loadsSuccessRecords_andPickOpensChat() {
        RecordItemVO failed = FakeAiRepository.record(2040, "FAILED", null, null, null);
        RecordItemVO tomato = FakeAiRepository.record(2039, "SUCCESS", "番茄早疫病", 0.88, "轻度");
        RecordItemVO bad = FakeAiRepository.record(2038, "IMAGE_BAD", null, null, null);
        repo.recentResult = Single.just(Arrays.asList(failed, tomato, bad, cucumber("SUCCESS")));
        AskAiViewModel vm = create();
        List<Integer> events = collect(vm);

        vm.onFollowCardClick();
        assertEquals(Collections.singletonList(AiConstants.RECENT_RECORD_COUNT), repo.recentSizes);
        assertEquals(Collections.singletonList(AskAiViewModel.Event.SHOW_RECORD_PICKER), events);
        assertEquals(UiState.SUCCESS, vm.getRecordChoiceState().getValue());
        List<RecordItemVO> choices = vm.getRecordChoices().getValue();
        assertEquals("只列出识别成功的记录", 2, choices.size());
        assertEquals(2039, choices.get(0).getId());
        assertEquals(2036, choices.get(1).getId());

        vm.pickRecord(choices.get(0));
        AskAiViewModel.Event event = vm.getEvent().getValue();
        assertEquals(AskAiViewModel.Event.OPEN_CHAT, event.type);
        assertEquals(2039, event.args.recordId);
        assertNull(event.args.question);
    }

    @Test
    public void followCard_emptyAndError() {
        repo.recentResult = Single.just(Collections.singletonList(cucumber("FAILED")));
        AskAiViewModel vm = create();
        vm.onFollowCardClick();
        assertEquals(UiState.EMPTY, vm.getRecordChoiceState().getValue());
        assertTrue(vm.getRecordChoices().getValue().isEmpty());
        vm.openScan();
        assertEquals(AskAiViewModel.Event.OPEN_SCAN, vm.getEvent().getValue().type);

        repo.recentResult = Single.error(new IllegalStateException("IRecordService 未注册"));
        vm.loadRecordChoices();
        assertEquals(UiState.ERROR, vm.getRecordChoiceState().getValue());

        repo.recentResult = Single.just(Collections.singletonList(cucumber("SUCCESS")));
        vm.loadRecordChoices();
        assertEquals(UiState.SUCCESS, vm.getRecordChoiceState().getValue());
        assertFalse(vm.getRecordChoices().getValue().isEmpty());
    }

    @Test
    public void onResume_reloadsWhenGuestStateChanges() {
        repo.guest = true;
        AskAiViewModel vm = create();
        vm.loadSuggestions();
        assertEquals(0, repo.suggestionsCalls);

        repo.guest = false;
        repo.suggestionsResult = Single.just(Collections.singletonList(FakeAiRepository.suggestion(9, "登录后的问题")));
        vm.onResume();
        assertEquals(1, repo.suggestionsCalls);
        assertEquals("登录后的问题", vm.getSuggestions().getValue().get(0).getText());
        assertEquals(1, repo.recentCalls);
    }
}
