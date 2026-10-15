package com.heban.pestai.recognize.ui.analyzing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

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
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.schedulers.TestScheduler;

public class AnalyzingViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public TrampolineSchedulerRule schedulerRule = new TrampolineSchedulerRule();

    private final FakeTexts texts = new FakeTexts();
    /** 主线程调度器换成可拨动的虚拟时钟，验证「完成 500ms 后自动进入 R01」 */
    private final TestScheduler clock = new TestScheduler();
    private AnalyzingViewModel vm;
    private FakeTask task;
    private final List<AnalyzeEvent> events = new ArrayList<>();

    @Before
    public void setUp() {
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> clock);
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
        assertTrue("先展示完成状态，不立即跳转", events.isEmpty());

        advance(AnalyzingViewModel.AUTO_RESULT_DELAY_MS);
        assertEquals(1, events.size());
        AnalyzeEvent event = events.get(0);
        assertEquals(AnalyzeEvent.Type.GO_RESULT, event.type);
        assertEquals(1024, event.recordId);
        assertNotNull(event.recordJson);
        assertEquals(result, Samples.GSON.fromJson(event.recordJson, RecognitionVO.class));
    }

    // ---------- 识别成功后自动进入 R01 ----------

    @Test
    public void success_autoResultAfter500ms() {
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        advance(AnalyzingViewModel.AUTO_RESULT_DELAY_MS - 1);
        assertTrue("499ms 时仍停留在完成状态", events.isEmpty());
        assertEquals(texts.get(R.string.scan_analyzing_title_done), vm.getTitle().getValue());

        advance(1);
        assertEquals(1, events.size());
        assertEquals(AnalyzeEvent.Type.GO_RESULT, events.get(0).type);

        advance(10_000);
        assertEquals("只自动跳转一次", 1, events.size());
    }

    @Test
    public void success_replayedSnapshot_schedulesOnlyOnce() {
        // Service 重新绑定时会回放 SUCCESS 快照
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        advance(300);
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        advance(200);
        assertEquals("按第一次成功计时", 1, events.size());
        advance(10_000);
        assertEquals(1, events.size());
    }

    @Test
    public void backDuringDoneState_goesToResultImmediately_once() {
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        advance(200);
        vm.onBackPressed();
        assertEquals(1, events.size());
        assertEquals(AnalyzeEvent.Type.GO_RESULT, events.get(0).type);

        advance(10_000);
        vm.onBackPressed();
        assertEquals("计时器与再次返回都不会重复跳转", 1, events.size());
    }

    @Test
    public void onCleared_cancelsAutoResult() {
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        vm.onCleared();
        advance(10_000);
        assertTrue(events.isEmpty());
    }

    @Test
    public void autoResult_whileStopped_isDeliveredOnceWhenStartedAgain() {
        AnalyzingViewModel fresh = new AnalyzingViewModel(Samples.GSON, texts);
        fresh.init("file:///cache/scan/IMG_1.jpg", "PEST");
        List<AnalyzeEvent> received = new ArrayList<>();

        // 页面已 onStop（用户按 Home 离开）：计时器到点时不投递
        TestOwner page = new TestOwner(Lifecycle.State.STARTED);
        fresh.getEvent().observe(page, received::add);
        fresh.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        page.moveTo(Lifecycle.State.CREATED);
        advance(AnalyzingViewModel.AUTO_RESULT_DELAY_MS);
        assertTrue(received.isEmpty());

        // 回到前台立即进入 R01，之后再切换前后台不会重复
        page.moveTo(Lifecycle.State.STARTED);
        assertEquals(1, received.size());
        assertEquals(AnalyzeEvent.Type.GO_RESULT, received.get(0).type);
        page.moveTo(Lifecycle.State.CREATED);
        page.moveTo(Lifecycle.State.STARTED);
        assertEquals(1, received.size());
    }

    @Test
    public void autoResult_firedDuringRotation_isDeliveredToNewPage() {
        AnalyzingViewModel fresh = new AnalyzingViewModel(Samples.GSON, texts);
        fresh.init("file:///cache/scan/IMG_1.jpg", "PEST");
        List<AnalyzeEvent> received = new ArrayList<>();
        TestOwner oldPage = new TestOwner(Lifecycle.State.STARTED);
        fresh.getEvent().observe(oldPage, received::add);
        fresh.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        advance(200);

        // 旋转：旧页面销毁，计时器（在 ViewModel 中）继续，在新页面创建前到点
        oldPage.moveTo(Lifecycle.State.DESTROYED);
        advance(300);
        assertTrue(received.isEmpty());

        TestOwner newPage = new TestOwner(Lifecycle.State.CREATED);
        fresh.getEvent().observe(newPage, received::add);
        newPage.moveTo(Lifecycle.State.STARTED);
        assertEquals(1, received.size());
        assertEquals(AnalyzeEvent.Type.GO_RESULT, received.get(0).type);
    }

    private void advance(long ms) {
        clock.advanceTimeBy(ms, TimeUnit.MILLISECONDS);
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
        advance(AnalyzingViewModel.AUTO_RESULT_DELAY_MS);
        assertEquals(1, events.size());
        assertEquals(AnalyzeEvent.Type.GO_RESULT, events.get(0).type);
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
    public void cancel_afterSuccess_goesToResultImmediately() {
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        vm.cancel();
        assertEquals(0, task.cancelCount);
        assertEquals(1, events.size());
        assertEquals(AnalyzeEvent.Type.GO_RESULT, events.get(0).type);
    }

    @Test
    public void backPressed_confirmsWhileLoading_resultAfterDone() {
        vm.onBackPressed();
        assertEquals(AnalyzeEvent.Type.CONFIRM_CANCEL, events.get(0).type);
        vm.onStateChanged(RecognizeState.success(1, 1, Samples.cucumber()));
        vm.onBackPressed();
        assertEquals(AnalyzeEvent.Type.GO_RESULT, events.get(1).type);
    }

    @Test
    public void backPressed_afterUnauthorized_closes() {
        vm.onStateChanged(RecognizeState.failed(RecognizeState.compressing(), 40101, FailReason.NETWORK, "登录过期"));
        vm.onBackPressed();
        assertEquals(AnalyzeEvent.Type.CLOSE, events.get(1).type);
        advance(10_000);
        assertEquals("失败不会自动进入 R01", 2, events.size());
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

    /** 可手动切换生命周期状态的页面 */
    private static final class TestOwner implements LifecycleOwner {
        private final LifecycleRegistry registry = LifecycleRegistry.createUnsafe(this);

        TestOwner(@NonNull Lifecycle.State state) {
            registry.setCurrentState(state);
        }

        void moveTo(@NonNull Lifecycle.State state) {
            registry.setCurrentState(state);
        }

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return registry;
        }
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
