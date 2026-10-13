package com.heban.pestai.recognize.ui.analyzing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.AnalyzeEvent;
import com.heban.pestai.recognize.model.RecognizeMode;
import com.heban.pestai.recognize.model.RecognizeState;
import com.heban.pestai.recognize.model.StepState;
import com.heban.pestai.recognize.service.ProgressListener;
import com.heban.pestai.recognize.service.RecognizeTask;
import com.heban.pestai.recognize.testing.FakeTexts;
import com.heban.pestai.recognize.testing.Samples;
import com.heban.pestai.recognize.testing.TrampolineSchedulerRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AnalyzingViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public TrampolineSchedulerRule schedulerRule = new TrampolineSchedulerRule();

    private final FakeTexts texts = new FakeTexts();
    private AnalyzingViewModel vm;
    private FakeTask task;
    private final List<AnalyzeEvent> events = new ArrayList<>();

    @Before
    public void setUp() {
        vm = new AnalyzingViewModel(Samples.GSON, texts);
        vm.getEvent().observeForever(e -> events.add(e));
        vm.init("file:///cache/scan/IMG_1.jpg", "PEST");
        task = new FakeTask();
        vm.attach(task);
    }

    @Test
    public void init_showsCompressingState() {
        assertSame(vm, task.listener);
        assertEquals(texts.get(R.string.scan_analyzing_title), vm.getTitle().getValue());
        assertEquals(texts.get(R.string.scan_analyzing_status_compressing), vm.getStatusText().getValue());
        assertEquals("0%", vm.getProgressText().getValue());
        assertEquals(Arrays.asList(StepState.RUNNING, StepState.WAITING, StepState.WAITING),
                vm.getStepStates().getValue());
        assertEquals(texts.get(R.string.scan_step_compressing), vm.getCompressText().getValue());
        assertEquals(texts.get(R.string.scan_step_waiting), vm.getUploadText().getValue());
        assertEquals(UiState.LOADING, vm.getUiState().getValue());
        assertEquals(RecognizeMode.PEST, vm.getRecognizeMode());
        assertFalse(vm.isCropMode());
    }

    @Test
    public void init_onlyOnce() {
        vm.init("other", "CROP");
        assertEquals("file:///cache/scan/IMG_1.jpg", vm.getImageUri());
        assertEquals(RecognizeMode.PEST, vm.getRecognizeMode());
    }

    @Test
    public void compressing_uploading_analyzing_success() {
        vm.onStateChanged(RecognizeState.compressing());
        assertEquals(0, (int) vm.getProgress().getValue());

        vm.onStateChanged(RecognizeState.uploading(1258291, 327680, 50));
        assertEquals(40, (int) vm.getProgress().getValue());
        assertEquals("40%", vm.getProgressText().getValue());
        assertEquals(texts.get(R.string.scan_analyzing_status_uploading), vm.getStatusText().getValue());
        assertEquals(Arrays.asList(StepState.DONE, StepState.RUNNING, StepState.WAITING),
                vm.getStepStates().getValue());
        assertEquals("1.2 MB → 320 KB", vm.getCompressText().getValue());
        assertEquals("50%", vm.getUploadText().getValue());
        assertEquals(texts.get(R.string.scan_step_waiting), vm.getAnalyzeText().getValue());

        vm.onStateChanged(RecognizeState.analyzing(1258291, 327680, 72));
        assertEquals("72%", vm.getProgressText().getValue());
        assertEquals(texts.get(R.string.scan_analyzing_status_analyzing), vm.getStatusText().getValue());
        assertEquals(Arrays.asList(StepState.DONE, StepState.DONE, StepState.RUNNING),
                vm.getStepStates().getValue());
        assertEquals(texts.get(R.string.scan_step_done), vm.getUploadText().getValue());
        assertEquals(texts.get(R.string.scan_step_running), vm.getAnalyzeText().getValue());
        assertEquals(UiState.LOADING, vm.getUiState().getValue());
        assertFalse(vm.isFinished());

        RecognitionVO result = Samples.cucumber();
        vm.onStateChanged(RecognizeState.success(1258291, 327680, result));
        assertEquals("100%", vm.getProgressText().getValue());
        assertEquals(texts.get(R.string.scan_analyzing_title_done), vm.getTitle().getValue());
        assertEquals(texts.get(R.string.scan_analyzing_status_done), vm.getStatusText().getValue());
        assertEquals(Arrays.asList(StepState.DONE, StepState.DONE, StepState.DONE), vm.getStepStates().getValue());
        assertEquals(texts.get(R.string.scan_step_done), vm.getAnalyzeText().getValue());
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertTrue(vm.isFinished());
        assertEquals(1, task.finishCount);
        assertTrue("成功后停留在 S02，不自动跳转", events.isEmpty());

        vm.viewResult();
        AnalyzeEvent event = events.get(events.size() - 1);
        assertEquals(AnalyzeEvent.Type.GO_RESULT, event.type);
        assertEquals(1024, event.recordId);
        assertNotNull(event.recordJson);
        assertEquals(result, Samples.GSON.fromJson(event.recordJson, RecognitionVO.class));
    }

    @Test
    public void failed_imageBad_goesToR02() {
        vm.onStateChanged(RecognizeState.failed(RecognizeState.analyzing(1, 1, 80), 42201,
                FailReason.IMAGE_BAD, "照片不清晰，无法识别"));
        assertEquals(UiState.ERROR, vm.getUiState().getValue());
        assertEquals(1, events.size());
        AnalyzeEvent event = events.get(0);
        assertEquals(AnalyzeEvent.Type.GO_ERROR, event.type);
        assertEquals(ErrorType.IMAGE_BAD, event.errorType);
        assertEquals(FailReason.IMAGE_BAD, event.failReason);
        assertEquals(1, task.finishCount);
        assertTrue(vm.isFinished());
    }

    @Test
    public void failed_timeout_goesToR02Failed() {
        vm.onStateChanged(RecognizeState.failed(RecognizeState.analyzing(1, 1, 80), 50401,
                FailReason.TIMEOUT, "识别超时"));
        AnalyzeEvent event = events.get(0);
        assertEquals(ErrorType.FAILED, event.errorType);
        assertEquals(FailReason.TIMEOUT, event.failReason);
    }

    @Test
    public void idle_meansInterrupted() {
        vm.onStateChanged(RecognizeState.idle());
        AnalyzeEvent event = events.get(0);
        assertEquals(AnalyzeEvent.Type.GO_ERROR, event.type);
        assertEquals(ErrorType.FAILED, event.errorType);
        assertEquals(FailReason.NETWORK, event.failReason);
    }

    @Test
    public void unauthorized_closesWithoutR02() {
        vm.onStateChanged(RecognizeState.failed(RecognizeState.compressing(), 40101, FailReason.NETWORK, "登录过期"));
        assertEquals(AnalyzeEvent.Type.CLOSE, events.get(0).type);
    }

    @Test
    public void statesAfterFinish_areIgnored() {
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        vm.onStateChanged(RecognizeState.failed(RecognizeState.idle(), 50201, FailReason.UNAVAILABLE, "x"));
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertTrue(events.isEmpty());
    }

    @Test
    public void cancel_whileLoading_cancelsTask() {
        vm.onStateChanged(RecognizeState.uploading(1, 1, 30));
        vm.cancel();
        assertEquals(1, task.cancelCount);
        assertEquals(AnalyzeEvent.Type.CANCELED, events.get(0).type);
        assertTrue(vm.isFinished());
        // Service 回放 CANCELED 不再产生事件
        vm.onStateChanged(RecognizeState.canceled(RecognizeState.idle()));
        assertEquals(1, events.size());
    }

    @Test
    public void cancel_afterSuccess_closes() {
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        vm.cancel();
        assertEquals(0, task.cancelCount);
        assertEquals(AnalyzeEvent.Type.CLOSE, events.get(0).type);
    }

    @Test
    public void backPressed_confirmsWhileLoading_closesAfterDone() {
        vm.onBackPressed();
        assertEquals(AnalyzeEvent.Type.CONFIRM_CANCEL, events.get(0).type);
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        vm.onBackPressed();
        assertEquals(AnalyzeEvent.Type.CLOSE, events.get(1).type);
    }

    @Test
    public void detach_releasesTask() {
        vm.detach();
        assertEquals(1, task.detachCount);
        vm.cancel();
        assertEquals("已解绑时不再调用 binder", 0, task.cancelCount);
    }

    @Test
    public void cropMode() {
        AnalyzingViewModel crop = new AnalyzingViewModel(Samples.GSON, texts);
        crop.init("content://media/picker/0/1", "CROP");
        assertTrue(crop.isCropMode());
        assertEquals("CROP", crop.getRecognizeMode().value());
    }

    private static final class FakeTask implements RecognizeTask {
        ProgressListener listener;
        int detachCount;
        int cancelCount;
        int finishCount;

        @Override
        public void attach(@NonNull ProgressListener progressListener) {
            listener = progressListener;
        }

        @Override
        public void detach() {
            detachCount++;
            listener = null;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public void finishTask() {
            finishCount++;
        }
    }
}
