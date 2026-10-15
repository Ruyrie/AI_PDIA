package com.heban.pestai.recognize.ui.result;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.data.RecognizeRepository;
import com.heban.pestai.recognize.model.OtherItem;
import com.heban.pestai.recognize.model.RecognizeMode;
import com.heban.pestai.recognize.model.ResultEvent;
import com.heban.pestai.recognize.model.SeverityLevel;
import com.heban.pestai.recognize.model.TagItem;
import com.heban.pestai.recognize.util.Formats;
import com.heban.pestai.recognize.util.RecognizeErrors;
import com.heban.pestai.recognize.util.TextSource;
import com.heban.pestai.recognize.util.TimeText;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

/**
 * R01 识别结果：两种入口（record_json 直接显示 / 只带 record_id 时请求详情）、
 * 由 RecognitionVO 派生的显示字段、「其他可能」的展开状态、「继续识别」与其他跳转事件。
 */
public class ResultViewModel extends BaseViewModel {

    private static final String TAG = "ResultViewModel";

    private final RecognizeRepository repository;
    private final Gson gson;
    private final TextSource texts;

    private final MutableLiveData<RecognitionVO> record = new MutableLiveData<>();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.LOADING);
    private final MutableLiveData<String> errorText = new MutableLiveData<>("");
    /** 加载失败时按钮为「重试」（true）还是「返回」（false，记录不存在） */
    private final MutableLiveData<Boolean> errorRetryable = new MutableLiveData<>(true);
    private final MutableLiveData<String> photoLabel = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> showBox = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> confidenceProgress = new MutableLiveData<>(0);
    private final MutableLiveData<List<TagItem>> tags = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> footerText = new MutableLiveData<>("");
    /** 「其他可能」各行（含展开状态） */
    private final MutableLiveData<List<OtherItem>> others = new MutableLiveData<>(Collections.emptyList());
    /** 有可展开的行时在「其他可能」标题下显示「点按查看判断依据」 */
    private final MutableLiveData<Boolean> othersHintVisible = new MutableLiveData<>(false);
    private final SingleLiveEvent<ResultEvent> event = new SingleLiveEvent<>();

    /** 已展开的「其他可能」（按候选名称记录，旋转屏幕后保持） */
    private final Set<String> expandedOthers = new LinkedHashSet<>();
    private boolean initialized;
    /** R01 正下方是拍摄本记录的 S01（S01 → S02 → R01） */
    private boolean fromScan;
    private long recordId = -1;
    /** 当前 VO（包括转 R02 前的非成功记录） */
    @Nullable
    private RecognitionVO current;
    /** 入场动画是否已播放（放在 ViewModel 中，旋转屏幕后不重播） */
    private boolean animated;

    @Inject
    public ResultViewModel(RecognizeRepository repository, Gson gson, TextSource texts) {
        this.repository = repository;
        this.gson = gson;
        this.texts = texts;
    }

    /**
     * 只执行一次：有 record_json 时解析后直接显示（不请求）；没有或解析失败时按 record_id 请求详情。
     *
     * @param fromScan 由 S02 带入（S02 由 S01 启动）：R01 关闭后回到的就是拍摄本记录的 S01
     */
    public void init(long id, @Nullable String recordJson, boolean fromScan) {
        if (initialized) {
            return;
        }
        initialized = true;
        this.fromScan = fromScan;
        recordId = id;
        RecognitionVO parsed = parse(recordJson);
        if (parsed != null) {
            recordId = parsed.getId();
            show(parsed);
        } else {
            load();
        }
    }

    @Nullable
    private RecognitionVO parse(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            RecognitionVO vo = gson.fromJson(json, RecognitionVO.class);
            return vo != null && vo.getId() > 0 ? vo : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "record_json 解析失败，改为按 record_id 请求", e);
            return null;
        }
    }

    private void load() {
        if (recordId <= 0) {
            showError(texts.get(R.string.scan_result_not_found), false);
            return;
        }
        uiState.setValue(UiState.LOADING);
        add(repository.getDetail(recordId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::show, this::onLoadError));
    }

    private void onLoadError(@NonNull Throwable t) {
        int code = ApiException.codeOf(t);
        if (code == ApiException.PARAM_ERROR) {
            showError(texts.get(R.string.scan_result_not_found), false);
        } else if (code == ApiException.NETWORK || code == ApiException.TIMEOUT) {
            showError(texts.get(R.string.scan_err_network), true);
        } else {
            showError(ApiException.messageOf(t), true);
        }
    }

    private void showError(@NonNull String message, boolean retryable) {
        errorText.setValue(message);
        errorRetryable.setValue(retryable);
        uiState.setValue(UiState.ERROR);
    }

    /** 错误状态点「重试」 */
    public void reload() {
        load();
    }

    /** 错误状态的按钮：可重试时重新请求，记录不存在时关闭页面 */
    public void onErrorAction() {
        if (Boolean.TRUE.equals(errorRetryable.getValue())) {
            reload();
        } else {
            event.setValue(ResultEvent.CLOSE);
        }
    }

    /** 统一显示：不是识别成功的记录转到 R02，否则写入 record 并派生显示字段 */
    void show(@NonNull RecognitionVO vo) {
        current = vo;
        if (!RecognitionStatus.SUCCESS.equals(vo.getStatus())) {
            event.setValue(ResultEvent.GO_ERROR);
            return;
        }
        photoLabel.setValue(texts.get(Formats.modeLabelRes(vo.getMode())) + " · "
                + TimeText.of(vo.getCreatedAt(), LocalDateTime.now()));
        showBox.setValue(isShowBox(vo.getMode(), vo.getSeverity()));
        confidenceProgress.setValue(Formats.percent(vo.getConfidence()));
        tags.setValue(TagItem.listOf(vo));
        footerText.setValue(texts.get(R.string.scan_result_footer, Formats.modelName(vo.getModelName())));
        renderOthers(vo);
        record.setValue(vo);
        uiState.setValue(UiState.SUCCESS);
    }

    /** 识别框：病虫害模式且结果不是「健康」时显示 */
    public static boolean isShowBox(@Nullable String mode, @Nullable String severity) {
        return !Mode.CROP.equals(mode) && SeverityLevel.of(severity) != SeverityLevel.HEALTHY;
    }

    // ---------- 其他可能 ----------

    /** 由记录的 others 生成各行；「与{最可能结果}的区别」用记录的 topName */
    private void renderOthers(@NonNull RecognitionVO vo) {
        String top = vo.getTopName() == null ? "" : vo.getTopName().trim();
        String label = top.isEmpty() ? texts.get(R.string.scan_result_other_difference_default)
                : texts.get(R.string.scan_result_other_difference, top);
        List<OtherItem> items = OtherItem.listOf(vo.getOthers(), label, expandedOthers);
        others.setValue(items);
        othersHintVisible.setValue(OtherItem.anyExpandable(items));
    }

    /** 点按「其他可能」的一行：展开 / 收起判断依据；没有判断依据的行忽略 */
    public void toggleOther(@Nullable String name) {
        RecognitionVO vo = record.getValue();
        if (name == null || vo == null || !isExpandable(name)) {
            return;
        }
        if (!expandedOthers.remove(name)) {
            expandedOthers.add(name);
        }
        renderOthers(vo);
    }

    private boolean isExpandable(@NonNull String name) {
        List<OtherItem> items = others.getValue();
        if (items != null) {
            for (OtherItem item : items) {
                if (name.equals(item.getName())) {
                    return item.isExpandable();
                }
            }
        }
        return false;
    }

    // ---------- 底部按钮 ----------

    /**
     * 「继续识别」：用本记录的模式回到拍照页。R01 正下方就是拍摄本记录的 S01 时直接关闭 R01（BACK_TO_SCAN），
     * 否则打开 S01 并关闭 R01（OPEN_SCAN）。两种情况在相机页按返回都回到进入识别前的页面，不会回到旧结果。
     */
    public void continueScan() {
        if (record.getValue() == null) {
            return;
        }
        event.setValue(fromScan ? ResultEvent.BACK_TO_SCAN : ResultEvent.OPEN_SCAN);
    }

    public void askAi() {
        if (record.getValue() != null) {
            event.setValue(ResultEvent.ASK_AI);
        }
    }

    public void openHistory() {
        event.setValue(ResultEvent.OPEN_HISTORY);
    }

    // ---------- 动画标记 ----------

    public boolean isAnimated() {
        return animated;
    }

    public void markAnimated() {
        animated = true;
    }

    // ---------- 跳转参数 ----------

    /** 当前记录 id（追问 AI、转 R02 使用） */
    public long getRecordId() {
        return current != null ? current.getId() : recordId;
    }

    @Nullable
    public RecognitionVO getCurrent() {
        return current;
    }

    /** 打开 S01 的 EXTRA_MODE：本记录的模式（PEST / CROP，空值按 PEST） */
    @NonNull
    public String getScanMode() {
        return RecognizeMode.of(current == null ? null : current.getMode()).value();
    }

    /** 转 R02 的异常类型：记录状态 IMAGE_BAD → IMAGE_BAD，其余 FAILED */
    @NonNull
    public String getErrorType() {
        return RecognizeErrors.typeOfStatus(current == null ? null : current.getStatus());
    }

    @Nullable
    public String getFailReason() {
        return current == null ? null : current.getFailReason();
    }

    // ---------- getter ----------

    public LiveData<RecognitionVO> getRecord() {
        return record;
    }

    public LiveData<UiState> getUiState() {
        return uiState;
    }

    public LiveData<String> getErrorText() {
        return errorText;
    }

    public LiveData<Boolean> getErrorRetryable() {
        return errorRetryable;
    }

    public LiveData<String> getPhotoLabel() {
        return photoLabel;
    }

    public LiveData<Boolean> getShowBox() {
        return showBox;
    }

    public LiveData<Integer> getConfidenceProgress() {
        return confidenceProgress;
    }

    public LiveData<List<TagItem>> getTags() {
        return tags;
    }

    public LiveData<String> getFooterText() {
        return footerText;
    }

    public LiveData<List<OtherItem>> getOthers() {
        return others;
    }

    public LiveData<Boolean> getOthersHintVisible() {
        return othersHintVisible;
    }

    public LiveData<ResultEvent> getEvent() {
        return event;
    }
}
