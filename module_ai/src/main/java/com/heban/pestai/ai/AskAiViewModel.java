package com.heban.pestai.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.ai.chat.ChatArgs;
import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.ai.util.AiFormat;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SuggestionVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Q01 新对话：大家常问、最近识别、输入与待发送图片、提问模板、三张能力卡片的直达操作、游客判断。
 * Q01 只负责「发起」，发送、点大家常问、点继续追问、选择要追问的识别记录都发出 OPEN_CHAT 事件打开 Q02，
 * 对话逻辑只在 ChatViewModel 中。
 * <ul>
 *     <li>「文字提问」：输入框获得焦点并弹出键盘，输入栏上方显示提问模板；点模板填入输入框（不发送）；
 *     发送、清空输入或收起键盘后模板行隐藏；</li>
 *     <li>「带图提问」：弹出「拍照 / 从相册选择」；选好照片后作为待发送图片，输入框获得焦点；</li>
 *     <li>「追问结果」：弹出最近 10 条中识别成功的记录，点一条打开 Q02 追问；没有记录时可去拍照识别；</li>
 *     <li>右上角「历史对话」打开 Q03。游客的以上操作都弹出「登录后才能使用」。</li>
 * </ul>
 */
public class AskAiViewModel extends BaseViewModel {

    /** 一次性事件 */
    public static final class Event {
        public static final int NEED_LOGIN = 1;
        public static final int OPEN_CHAT = 2;
        /** 打开系统照片选择器 */
        public static final int PICK_IMAGE = 3;
        /** 打开 Q03 历史对话 */
        public static final int OPEN_HISTORY = 4;
        /** 输入框获得焦点（光标在末尾）并弹出键盘 */
        public static final int FOCUS_INPUT = 5;
        /** 弹出「拍照 / 从相册选择 / 取消」 */
        public static final int SHOW_IMAGE_SOURCE = 6;
        /** 申请相机权限后打开系统相机 */
        public static final int TAKE_PHOTO = 7;
        /** 弹出「选择要追问的识别记录」 */
        public static final int SHOW_RECORD_PICKER = 8;
        /** 没有识别记录时「去拍照识别」（S01） */
        public static final int OPEN_SCAN = 9;

        public final int type;
        @Nullable
        public final ChatArgs args;

        Event(int type, @Nullable ChatArgs args) {
            this.type = type;
            this.args = args;
        }
    }

    private final AiRepository repository;

    private final MutableLiveData<List<SuggestionVO>> suggestions = new MutableLiveData<>(Collections.emptyList());
    /** 大家常问区域：LOADING 显示占位条 / SUCCESS / EMPTY 隐藏整个区域 */
    private final MutableLiveData<UiState> suggestionState = new MutableLiveData<>(UiState.LOADING);
    private final MutableLiveData<RecordItemVO> latestRecord = new MutableLiveData<>();
    private final MutableLiveData<String> latestLabel = new MutableLiveData<>("");
    private final MutableLiveData<String> latestTitle = new MutableLiveData<>("");
    private final MutableLiveData<String> inputText = new MutableLiveData<>("");
    /** 待发送图片的本地 Uri 字符串 */
    private final MutableLiveData<String> pendingImage = new MutableLiveData<>();
    private final MediatorLiveData<Boolean> canSend = new MediatorLiveData<>();
    private final MutableLiveData<Boolean> guest = new MutableLiveData<>();
    private final SingleLiveEvent<Event> event = new SingleLiveEvent<>();
    /** 输入栏上方的提问模板行 */
    private final MediatorLiveData<Boolean> templatesVisible = new MediatorLiveData<>();
    /** 「选择要追问的识别记录」列表：识别成功的最近记录 */
    private final MutableLiveData<List<RecordItemVO>> recordChoices = new MutableLiveData<>(Collections.emptyList());
    /** 记录列表状态：LOADING / SUCCESS / EMPTY / ERROR */
    private final MutableLiveData<UiState> recordChoiceState = new MutableLiveData<>(UiState.LOADING);

    private boolean suggestionsLoaded;
    @Nullable
    private Disposable suggestionTask;
    @Nullable
    private Disposable latestTask;
    @Nullable
    private Disposable choiceTask;
    /** 上一次输入框是否有文字（从有到无即「清空输入」） */
    private boolean hadText;

    @Inject
    public AskAiViewModel(@NonNull AiRepository repository) {
        this.repository = repository;
        guest.setValue(repository.isGuest());
        canSend.setValue(false);
        canSend.addSource(inputText, v -> updateCanSend());
        canSend.addSource(pendingImage, v -> updateCanSend());
        templatesVisible.setValue(false);
        templatesVisible.addSource(inputText, this::onInputChanged);
    }

    // ==================== 对外数据 ====================

    public LiveData<List<SuggestionVO>> getSuggestions() {
        return suggestions;
    }

    public LiveData<UiState> getSuggestionState() {
        return suggestionState;
    }

    public LiveData<RecordItemVO> getLatestRecord() {
        return latestRecord;
    }

    public LiveData<String> getLatestLabel() {
        return latestLabel;
    }

    public LiveData<String> getLatestTitle() {
        return latestTitle;
    }

    public MutableLiveData<String> getInputText() {
        return inputText;
    }

    public LiveData<String> getPendingImage() {
        return pendingImage;
    }

    public LiveData<Boolean> getCanSend() {
        return canSend;
    }

    public LiveData<Boolean> getGuest() {
        return guest;
    }

    public LiveData<Boolean> getTemplatesVisible() {
        return templatesVisible;
    }

    /** 提问模板（「文字提问」卡片） */
    @NonNull
    public List<String> getTemplates() {
        return AiConstants.questionTemplates();
    }

    public LiveData<List<RecordItemVO>> getRecordChoices() {
        return recordChoices;
    }

    public LiveData<UiState> getRecordChoiceState() {
        return recordChoiceState;
    }

    public LiveData<Event> getEvent() {
        return event;
    }

    public boolean isGuest() {
        return Boolean.TRUE.equals(guest.getValue());
    }

    // ==================== 数据加载 ====================

    /** Fragment onResume：登录态变化时重新加载大家常问；每次回到前台刷新最近识别 */
    public void onResume() {
        boolean nowGuest = repository.isGuest();
        if (nowGuest != isGuest()) {
            guest.setValue(nowGuest);
            suggestionsLoaded = false;
            loadSuggestions();
        }
        refreshLatest();
    }

    /** 只请求一次（切换 Tab、旋转屏幕不重复请求）；游客或失败时使用内置问题 */
    public void loadSuggestions() {
        if (suggestionsLoaded) {
            return;
        }
        suggestionsLoaded = true;
        dispose(suggestionTask);
        if (isGuest()) {
            suggestions.setValue(AiConstants.defaultSuggestions());
            suggestionState.setValue(UiState.SUCCESS);
            return;
        }
        suggestionState.setValue(UiState.LOADING);
        suggestionTask = repository.suggestions()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showSuggestions, error -> {
                    // 加载失败不提示，使用内置的 3 条默认问题
                    suggestions.setValue(AiConstants.defaultSuggestions());
                    suggestionState.setValue(UiState.SUCCESS);
                });
        add(suggestionTask);
    }

    private void showSuggestions(@Nullable List<SuggestionVO> list) {
        List<SuggestionVO> result = new ArrayList<>();
        if (list != null) {
            for (SuggestionVO s : list) {
                if (s != null && !AiFormat.isBlank(s.getText())) {
                    result.add(s);
                    if (result.size() == AiConstants.SUGGESTION_COUNT) {
                        break;
                    }
                }
            }
        }
        suggestions.setValue(Collections.unmodifiableList(result));
        suggestionState.setValue(result.isEmpty() ? UiState.EMPTY : UiState.SUCCESS);
    }

    /** 最近一次识别：只取 1 条且必须是 SUCCESS；游客、没有记录、读取失败时隐藏卡片 */
    public void refreshLatest() {
        dispose(latestTask);
        if (isGuest()) {
            setLatest(null);
            return;
        }
        latestTask = repository.recentRecords(1)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(list -> setLatest(firstSuccess(list)), error -> setLatest(null));
        add(latestTask);
    }

    @Nullable
    private static RecordItemVO firstSuccess(@Nullable List<RecordItemVO> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        RecordItemVO first = list.get(0);
        return first != null && RecognitionStatus.SUCCESS.equals(first.getStatus()) ? first : null;
    }

    private void setLatest(@Nullable RecordItemVO record) {
        latestRecord.setValue(record);
        latestLabel.setValue(record == null ? "" : AiFormat.latestLabel(record, LocalDateTime.now()));
        latestTitle.setValue(record == null ? "" : AiFormat.latestTitle(record));
    }

    // ==================== 用户操作 ====================

    /** 游客点击输入框：不弹键盘，弹出登录对话框（登录用户的输入框自己获得焦点） */
    public void onInputClick() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
        }
    }

    public void onPickImageClick() {
        event.setValue(new Event(isGuest() ? Event.NEED_LOGIN : Event.PICK_IMAGE, null));
    }

    public void send() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        String text = AiFormat.trim(inputText.getValue());
        String image = pendingImage.getValue();
        if (text.isEmpty() && image == null) {
            return;
        }
        event.setValue(new Event(Event.OPEN_CHAT, ChatArgs.ofQuestion(text, image)));
        inputText.setValue("");
        pendingImage.setValue(null);
        templatesVisible.setValue(false);
    }

    public void askSuggestion(@NonNull SuggestionVO suggestion) {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        if (!AiFormat.isBlank(suggestion.getText())) {
            event.setValue(new Event(Event.OPEN_CHAT, ChatArgs.ofQuestion(suggestion.getText().trim(), null)));
        }
    }

    /** 点击最近识别卡 / 「继续追问」：带 record_id 打开 Q02 */
    public void continueAsk() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        RecordItemVO record = latestRecord.getValue();
        if (record != null) {
            event.setValue(new Event(Event.OPEN_CHAT, ChatArgs.ofRecord(record.getId())));
        }
    }

    public void setPendingImage(@Nullable String uri) {
        pendingImage.setValue(uri);
    }

    public void clearPendingImage() {
        pendingImage.setValue(null);
    }

    /** 拍照 / 相册选好照片：作为待发送图片，输入框获得焦点（提示文字变为「描述这张照片的问题…」） */
    public void onImagePicked(@NonNull String uri) {
        pendingImage.setValue(uri);
        event.setValue(new Event(Event.FOCUS_INPUT, null));
    }

    // ==================== 顶部栏与能力卡片 ====================

    /** 右上角「历史对话」 */
    public void onHistoryClick() {
        event.setValue(new Event(isGuest() ? Event.NEED_LOGIN : Event.OPEN_HISTORY, null));
    }

    /** 「文字提问」：输入框获得焦点、弹出键盘，并显示提问模板 */
    public void onTextCardClick() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        templatesVisible.setValue(true);
        event.setValue(new Event(Event.FOCUS_INPUT, null));
    }

    /** 点提问模板：填入输入框（不直接发送），光标在末尾 */
    public void onTemplateClick(@NonNull String template) {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        inputText.setValue(template);
        event.setValue(new Event(Event.FOCUS_INPUT, null));
    }

    /** 收起键盘：隐藏模板行 */
    public void onKeyboardHidden() {
        if (Boolean.TRUE.equals(templatesVisible.getValue())) {
            templatesVisible.setValue(false);
        }
    }

    /** 「带图提问」：弹出拍照 / 从相册选择 */
    public void onImageCardClick() {
        event.setValue(new Event(isGuest() ? Event.NEED_LOGIN : Event.SHOW_IMAGE_SOURCE, null));
    }

    /** 选择「拍照」：页面申请相机权限后打开系统相机 */
    public void onTakePhoto() {
        event.setValue(new Event(isGuest() ? Event.NEED_LOGIN : Event.TAKE_PHOTO, null));
    }

    /** 「追问结果」：弹出最近识别成功的记录供选择，同时加载列表 */
    public void onFollowCardClick() {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        loadRecordChoices();
        event.setValue(new Event(Event.SHOW_RECORD_PICKER, null));
    }

    /** 加载最近 10 条识别记录，只保留识别成功的（打开记录选择框、加载失败点「重试」时调用） */
    public void loadRecordChoices() {
        dispose(choiceTask);
        recordChoiceState.setValue(UiState.LOADING);
        choiceTask = repository.recentRecords(AiConstants.RECENT_RECORD_COUNT)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(list -> {
                    List<RecordItemVO> result = successOnly(list);
                    recordChoices.setValue(result);
                    recordChoiceState.setValue(result.isEmpty() ? UiState.EMPTY : UiState.SUCCESS);
                }, error -> {
                    recordChoices.setValue(Collections.emptyList());
                    recordChoiceState.setValue(UiState.ERROR);
                });
        add(choiceTask);
    }

    /** 选中一条识别记录：打开 Q02 追问该记录 */
    public void pickRecord(@NonNull RecordItemVO record) {
        if (isGuest()) {
            event.setValue(new Event(Event.NEED_LOGIN, null));
            return;
        }
        event.setValue(new Event(Event.OPEN_CHAT, ChatArgs.ofRecord(record.getId())));
    }

    /** 没有识别记录时「去拍照识别」 */
    public void openScan() {
        event.setValue(new Event(Event.OPEN_SCAN, null));
    }

    @NonNull
    static List<RecordItemVO> successOnly(@Nullable List<RecordItemVO> list) {
        List<RecordItemVO> result = new ArrayList<>();
        if (list != null) {
            for (RecordItemVO r : list) {
                if (r != null && RecognitionStatus.SUCCESS.equals(r.getStatus())) {
                    result.add(r);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** 输入从有文字变为空（清空输入）时隐藏模板行 */
    private void onInputChanged(@Nullable String text) {
        boolean has = text != null && !text.isEmpty();
        if (hadText && !has && Boolean.TRUE.equals(templatesVisible.getValue())) {
            templatesVisible.setValue(false);
        }
        hadText = has;
    }

    private void updateCanSend() {
        boolean value = !AiFormat.isBlank(inputText.getValue()) || pendingImage.getValue() != null;
        if (!Boolean.valueOf(value).equals(canSend.getValue())) {
            canSend.setValue(value);
        }
    }

    private void dispose(@Nullable Disposable task) {
        if (task != null) {
            disposables.remove(task);
        }
    }
}
