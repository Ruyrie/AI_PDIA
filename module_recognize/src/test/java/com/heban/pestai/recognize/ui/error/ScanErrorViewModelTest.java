package com.heban.pestai.recognize.ui.error;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.ErrorEvent;
import com.heban.pestai.recognize.testing.FakeRecordService;
import com.heban.pestai.recognize.testing.FakeRepository;
import com.heban.pestai.recognize.testing.FakeTexts;
import com.heban.pestai.recognize.testing.Samples;
import com.heban.pestai.recognize.testing.TrampolineSchedulerRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

public class ScanErrorViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public TrampolineSchedulerRule schedulerRule = new TrampolineSchedulerRule();

    private final FakeTexts texts = new FakeTexts();
    private FakeRepository repository;
    private FakeRecordService recordService;
    private ScanErrorViewModel vm;
    private final List<ErrorEvent> events = new ArrayList<>();

    @Before
    public void setUp() {
        repository = new FakeRepository();
        recordService = new FakeRecordService();
        ServiceHub.register(IRecordService.class, recordService);
        vm = new ScanErrorViewModel(repository, Samples.GSON, texts);
        vm.getEvent().observeForever(e -> events.add(e));
    }

    @Test
    public void imageBad_config() {
        vm.init(ErrorType.IMAGE_BAD, FailReason.IMAGE_BAD, 77, "http://x/files/rec/a.jpg", null, "PEST");
        assertEquals(ErrorType.IMAGE_BAD, vm.getErrorType().getValue());
        assertEquals(com.heban.pestai.base.R.drawable.ic_blur_on, (int) vm.getIcon().getValue());
        assertEquals(com.heban.pestai.base.R.attr.colorWarningContainer, (int) vm.getIconBgAttr().getValue());
        assertEquals(texts.get(R.string.scan_error_blur_title), vm.getTitle().getValue());
        assertEquals(texts.get(R.string.scan_error_blur_message), vm.getMessage().getValue());
        assertEquals(texts.get(R.string.scan_error_blur_tips_title), vm.getTipsTitle().getValue());
        assertEquals(Arrays.asList(texts.get(R.string.scan_error_blur_tip_1), texts.get(R.string.scan_error_blur_tip_2),
                texts.get(R.string.scan_error_blur_tip_3)), vm.getTips().getValue());
        assertEquals(texts.get(R.string.scan_error_action_recognize_again), vm.getSecondaryText().getValue());
        assertEquals(texts.get(R.string.scan_error_action_retake), vm.getPrimaryText().getValue());
        assertEquals("http://x/files/rec/a.jpg", vm.getPhoto().getValue());
        assertTrue(vm.getPrimaryVisible().getValue());
        assertTrue(vm.getSecondaryVisible().getValue());

        vm.onPrimaryClick();
        assertEquals(ErrorEvent.RETAKE, events.get(0));
        assertEquals("PEST", vm.getModeValue());
    }

    @Test
    public void failed_withoutRecord_twoTipsAndTimeoutMessage() {
        vm.init(ErrorType.FAILED, FailReason.TIMEOUT, -1, null, "file:///cache/scan/IMG_1.jpg", "CROP");
        assertEquals(texts.get(R.string.scan_error_failed_title), vm.getTitle().getValue());
        assertEquals(texts.get(R.string.scan_error_failed_message_timeout), vm.getMessage().getValue());
        assertEquals(2, vm.getTips().getValue().size());
        assertEquals(texts.get(R.string.scan_error_action_retake), vm.getSecondaryText().getValue());
        assertEquals(texts.get(R.string.scan_error_action_retry), vm.getPrimaryText().getValue());
        assertEquals("file:///cache/scan/IMG_1.jpg", vm.getPhoto().getValue());
        assertEquals("CROP", vm.getModeValue());
        assertFalse(vm.hasRecord());
    }

    @Test
    public void init_onlyOnce() {
        vm.init(ErrorType.IMAGE_BAD, null, 77, null, null, "PEST");
        vm.init(ErrorType.FAILED, FailReason.NETWORK, -1, null, null, "CROP");
        assertEquals(ErrorType.IMAGE_BAD, vm.getErrorType().getValue());
        assertEquals("PEST", vm.getModeValue());
    }

    @Test
    public void retry_withRecord_success_goesToResult() {
        vm.init(ErrorType.FAILED, FailReason.TIMEOUT, 77, "http://x/a.jpg", null, "PEST");
        RecognitionVO vo = Samples.cucumber();
        vo.setId(77);
        repository.retryResult = Single.just(vo);

        vm.onPrimaryClick();

        assertEquals(1, repository.retryCalls);
        assertEquals(Long.valueOf(77), repository.retryIds.get(0));
        assertEquals(1, recordService.changedCount);
        assertEquals(ErrorEvent.GO_RESULT, events.get(0));
        assertEquals(77, vm.getResultId());
        assertEquals(vo, Samples.GSON.fromJson(vm.getResultJson(), RecognitionVO.class));
        assertFalse(vm.getRetrying().getValue());
    }

    @Test
    public void retry_stillBlur_switchesToImageBad() {
        vm.init(ErrorType.FAILED, FailReason.TIMEOUT, 77, "http://x/a.jpg", null, "PEST");
        repository.retryResult = Single.error(ApiException.of(42201));
        vm.onPrimaryClick();
        assertEquals(ErrorType.IMAGE_BAD, vm.getErrorType().getValue());
        assertEquals(texts.get(R.string.scan_error_blur_title), vm.getTitle().getValue());
        assertEquals(texts.get(R.string.scan_error_still_blur), vm.getToast().getValue());
        assertTrue(events.isEmpty());
    }

    @Test
    public void retry_networkError_staysFailedWithNetworkMessage() {
        vm.init(ErrorType.IMAGE_BAD, FailReason.IMAGE_BAD, 77, "http://x/a.jpg", null, "PEST");
        repository.retryResult = Single.error(ApiException.from(new UnknownHostException()));
        vm.onSecondaryClick();
        assertEquals(ErrorType.FAILED, vm.getErrorType().getValue());
        assertEquals(texts.get(R.string.scan_error_failed_message_network), vm.getMessage().getValue());
        assertEquals(texts.get(R.string.scan_err_network), vm.getToast().getValue());
        assertEquals(0, recordService.changedCount);
    }

    @Test
    public void retry_inProgress_showsLoadingOnRetryButtonOnly() {
        vm.init(ErrorType.IMAGE_BAD, FailReason.IMAGE_BAD, 77, "http://x/a.jpg", null, "PEST");
        repository.retryResult = Single.never();
        vm.onSecondaryClick();
        assertTrue(vm.getRetrying().getValue());
        assertTrue("IMAGE_BAD 的「重新识别」是左侧按钮", vm.getSecondaryLoading().getValue());
        assertFalse(vm.getPrimaryLoading().getValue());
        vm.onSecondaryClick();
        vm.onPrimaryClick();
        assertEquals(1, repository.retryCalls);
        assertTrue("识别中不响应重拍", events.isEmpty());
    }

    @Test
    public void retry_withoutRecord_reuploadsWhenPhotoExists() {
        vm.init(ErrorType.FAILED, FailReason.NETWORK, -1, null, "content://media/picker/0/1", "PEST");
        repository.readable = true;
        vm.onPrimaryClick();
        assertEquals(0, repository.retryCalls);
        assertEquals(ErrorEvent.REUPLOAD, events.get(0));
        assertEquals("content://media/picker/0/1", vm.getImageUri());
    }

    @Test
    public void retry_withoutRecord_photoMissing_keepsOnlyRetake() {
        vm.init(ErrorType.FAILED, FailReason.NETWORK, -1, null, "file:///cache/scan/gone.jpg", "PEST");
        repository.readable = false;
        vm.onPrimaryClick();
        assertTrue(events.isEmpty());
        assertEquals(texts.get(R.string.scan_error_photo_missing), vm.getToast().getValue());
        assertFalse("「重试」隐藏", vm.getPrimaryVisible().getValue());
        assertTrue("「重拍」保留", vm.getSecondaryVisible().getValue());
        assertFalse(vm.isRetryAvailable());
    }

    @Test
    public void openHistory() {
        vm.init(ErrorType.FAILED, null, 77, null, null, null);
        vm.openHistory();
        assertEquals(ErrorEvent.OPEN_HISTORY, events.get(0));
        assertEquals(texts.get(R.string.scan_error_failed_message_unavailable), vm.getMessage().getValue());
    }
}
