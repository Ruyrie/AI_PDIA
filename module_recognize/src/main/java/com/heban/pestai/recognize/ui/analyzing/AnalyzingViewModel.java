package com.heban.pestai.recognize.ui.analyzing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.AnalyzeEvent;
import com.heban.pestai.recognize.model.RecognizeMode;
import com.heban.pestai.recognize.model.RecognizeState;
import com.heban.pestai.recognize.model.StepState;
import com.heban.pestai.recognize.service.ProgressListener;
import com.heban.pestai.recognize.service.RecognizeTask;
import com.heban.pestai.recognize.util.Formats;
import com.heban.pestai.recognize.util.RecognizeErrors;
import com.heban.pestai.recognize.util.TextSource;

import java.util.List;

import javax.inject.Inject;

/**
 * S02 识别中：把 RecognizeService 的进度快照转换为标题、状态文字、进度、步骤状态，并发出跳转事件。
 * 作为 {@link ProgressListener} 由 {@link RecognizeTask#attach(ProgressListener)} 注册，回调都在主线程。
 */
public class AnalyzingViewModel extends BaseViewModel implements ProgressListener {

    private final Gson gson;
    private final TextSource texts;

    private final MutableLiveData<String> title = new MutableLiveData<>();
    private final MutableLiveData<String> statusText = new MutableLiveData<>();
    private final MutableLiveData<Integer> progress = new MutableLiveData<>(0);
    private final MutableLiveData<String> progressText = new MutableLiveData<>(Formats.progressText(0));
    private final MutableLiveData<List<StepState>> stepStates = new MutableLiveData<>();
    private final MutableLiveData<String> compressText = new MutableLiveData<>();
    private final MutableLiveData<String> uploadText = new MutableLiveData<>();
    private final MutableLiveData<String> analyzeText = new MutableLiveData<>();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.LOADING);
    private final SingleLiveEvent<AnalyzeEvent> event = new SingleLiveEvent<>();

    @Nullable
    private String imageUri;
    @NonNull
    private RecognizeMode mode = RecognizeMode.PEST;
    private boolean initialized;
    /** 已到终态（成功或失败）：Activity 重建后不再绑定 Service */
    private boolean finished;
    @Nullable
    private RecognitionVO result;
    @Nullable
    private RecognizeTask task;

    @Inject
    public AnalyzingViewModel(Gson gson, TextSource texts) {
        this.gson = gson;
        this.texts = texts;
    }

    /** 保存照片地址与模式（只在首次创建时生效），并显示压缩阶段的初始状态 */
    public void init(@Nullable String imageUri, @Nullable String extraMode) {
        if (initialized) {
            return;
        }
        initialized = true;
        this.imageUri = imageUri;
        this.mode = RecognizeMode.of(extraMode);
        render(RecognizeState.compressing());
    }

    public boolean isFinished() {
        return finished;
    }

    /** onServiceConnected：保存任务并注册监听，Service 立即回放当前快照 */
    public void attach(@NonNull RecognizeTask recognizeTask) {
        task = recognizeTask;
        recognizeTask.attach(this);
    }

    /** onStop / onServiceDisconnected：注销监听并释放引用，避免泄漏 Service */
    public void detach() {
        if (task != null) {
            task.detach();
            task = null;
        }
    }

    @Override
    public void onStateChanged(@NonNull RecognizeState state) {
        if (finished) {
            return;
        }
        switch (state.getPhase()) {
            case IDLE:
                // 进程被回收后绑定到新的空闲 Service：识别已中断
                fail(FailReason.NETWORK);
                break;
            case COMPRESSING:
            case UPLOADING:
            case ANALYZING:
                render(state);
                break;
            case SUCCESS:
                finished = true;
                result = state.getResult();
                render(state);
                uiState.setValue(UiState.SUCCESS);
                finishTask();
                break;
            case FAILED:
                if (RecognizeErrors.isUnauthorized(state.getErrorCode())) {
                    // 登录过期由 AuthInterceptor 统一跳转登录页，不进入 R02
                    finished = true;
                    uiState.setValue(UiState.ERROR);
                    finishTask();
                    event.setValue(AnalyzeEvent.of(AnalyzeEvent.Type.CLOSE));
                } else {
                    fail(state.getFailReason());
                }
                break;
            case CANCELED:
            default:
                // 取消由页面发起，事件已在 cancel() 中发出
                break;
        }
    }

    private void fail(@Nullable String failReason) {
        finished = true;
        String reason = failReason == null ? FailReason.NETWORK : failReason;
        uiState.setValue(UiState.ERROR);
        finishTask();
        event.setValue(AnalyzeEvent.error(RecognizeErrors.typeOfReason(reason), reason));
    }

    private void finishTask() {
        if (task != null) {
            task.finishTask();
        }
    }

    /** 快照 → 标题、状态文字、进度、步骤说明 */
    private void render(@NonNull RecognizeState state) {
        boolean done = state.getPhase() == RecognizeState.Phase.SUCCESS;
        title.setValue(texts.get(done ? R.string.scan_analyzing_title_done : R.string.scan_analyzing_title));
        statusText.setValue(texts.get(statusRes(state.getPhase())));
        progress.setValue(state.getProgress());
        progressText.setValue(Formats.progressText(state.getProgress()));
        List<StepState> steps = state.steps();
        stepStates.setValue(steps);
        compressText.setValue(compressText(steps.get(0), state));
        uploadText.setValue(stepText(steps.get(1), state, true));
        analyzeText.setValue(stepText(steps.get(2), state, false));
    }

    private static int statusRes(@NonNull RecognizeState.Phase phase) {
        switch (phase) {
            case UPLOADING:
                return R.string.scan_analyzing_status_uploading;
            case ANALYZING:
                return R.string.scan_analyzing_status_analyzing;
            case SUCCESS:
                return R.string.scan_analyzing_status_done;
            case COMPRESSING:
            default:
                return R.string.scan_analyzing_status_compressing;
        }
    }

    @NonNull
    private String compressText(@NonNull StepState step, @NonNull RecognizeState state) {
        switch (step) {
            case RUNNING:
                return texts.get(R.string.scan_step_compressing);
            case DONE:
                if (state.getOriginalSize() > 0 && state.getCompressedSize() > 0) {
                    return Formats.sizeChange(state.getOriginalSize(), state.getCompressedSize());
                }
                return texts.get(R.string.scan_step_done);
            case WAITING:
            default:
                return texts.get(R.string.scan_step_waiting);
        }
    }

    @NonNull
    private String stepText(@NonNull StepState step, @NonNull RecognizeState state, boolean upload) {
        switch (step) {
            case RUNNING:
                return upload ? Formats.progressText(state.getUploadPercent()) : texts.get(R.string.scan_step_running);
            case DONE:
                return texts.get(R.string.scan_step_done);
            case WAITING:
            default:
                return texts.get(R.string.scan_step_waiting);
        }
    }

    // ---------- 用户操作 ----------

    /** 「取消识别」：识别中取消任务并返回 S01；完成后直接返回 S01 */
    public void cancel() {
        if (finished) {
            event.setValue(AnalyzeEvent.of(AnalyzeEvent.Type.CLOSE));
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
        event.setValue(AnalyzeEvent.of(AnalyzeEvent.Type.CANCELED));
    }

    /** 返回键 / 返回箭头：识别中先确认，完成后直接返回 */
    public void onBackPressed() {
        if (finished) {
            event.setValue(AnalyzeEvent.of(AnalyzeEvent.Type.CLOSE));
        } else {
            event.setValue(AnalyzeEvent.of(AnalyzeEvent.Type.CONFIRM_CANCEL));
        }
    }

    /** 「查看结果」：Gson 序列化 RecognitionVO，进入 R01 */
    public void viewResult() {
        if (result != null) {
            event.setValue(AnalyzeEvent.result(result.getId(), gson.toJson(result)));
        }
    }

    @Override
    protected void onCleared() {
        detach();
        super.onCleared();
    }

    // ---------- getter ----------

    @Nullable
    public String getImageUri() {
        return imageUri;
    }

    @NonNull
    public RecognizeMode getRecognizeMode() {
        return mode;
    }

    /** 作物 / 杂草模式：模式标签与第三步名称不同 */
    public boolean isCropMode() {
        return mode == RecognizeMode.CROP;
    }

    @Nullable
    public RecognitionVO getResult() {
        return result;
    }

    public LiveData<String> getTitle() {
        return title;
    }

    public LiveData<String> getStatusText() {
        return statusText;
    }

    public LiveData<Integer> getProgress() {
        return progress;
    }

    public LiveData<String> getProgressText() {
        return progressText;
    }

    public LiveData<List<StepState>> getStepStates() {
        return stepStates;
    }

    public LiveData<String> getCompressText() {
        return compressText;
    }

    public LiveData<String> getUploadText() {
        return uploadText;
    }

    public LiveData<String> getAnalyzeText() {
        return analyzeText;
    }

    public LiveData<UiState> getUiState() {
        return uiState;
    }

    public LiveData<AnalyzeEvent> getEvent() {
        return event;
    }
}
