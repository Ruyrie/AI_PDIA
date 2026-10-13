package com.heban.pestai.recognize.ui.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.ResultEvent;
import com.heban.pestai.recognize.model.TagItem;
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
import java.util.List;

import io.reactivex.rxjava3.core.Single;

public class ResultViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public TrampolineSchedulerRule schedulerRule = new TrampolineSchedulerRule();

    private final FakeTexts texts = new FakeTexts();
    private FakeRepository repository;
    private FakeRecordService recordService;
    private ResultViewModel vm;
    private final List<ResultEvent> events = new ArrayList<>();

    @Before
    public void setUp() {
        repository = new FakeRepository();
        recordService = new FakeRecordService();
        ServiceHub.register(IRecordService.class, recordService);
        vm = new ResultViewModel(repository, Samples.GSON, texts);
        vm.getEvent().observeForever(e -> events.add(e));
    }

    @Test
    public void init_withJson_showsWithoutRequest() {
        RecognitionVO vo = Samples.cucumber();
        vm.init(vo.getId(), Samples.GSON.toJson(vo));

        assertEquals(0, repository.detailCalls);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertEquals(vo, vm.getRecord().getValue());
        assertEquals(92, (int) vm.getConfidenceProgress().getValue());
        assertTrue(vm.getShowBox().getValue());
        assertEquals(3, vm.getTags().getValue().size());
        assertEquals(texts.get(R.string.scan_result_footer, "DeepSeek"), vm.getFooterText().getValue());
        assertTrue(vm.getPhotoLabel().getValue().startsWith(texts.get(R.string.scan_mode_label_pest) + " · "));
        assertFalse(vm.isAnimated());
        assertEquals(1024, vm.getRecordId());
    }

    @Test
    public void init_withIdOnly_requestsOnce_evenAfterRotation() {
        repository.detailResult = Single.just(Samples.cucumber());
        vm.init(1024, null);
        vm.init(1024, null);
        assertEquals(1, repository.detailCalls);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
    }

    @Test
    public void init_invalidJson_fallsBackToRequest() {
        repository.detailResult = Single.just(Samples.cucumber());
        vm.init(1024, "{not json");
        assertEquals(1, repository.detailCalls);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
    }

    @Test
    public void init_noIdNoJson_showsNotFound() {
        vm.init(-1, null);
        assertEquals(0, repository.detailCalls);
        assertEquals(UiState.ERROR, vm.getUiState().getValue());
        assertEquals(texts.get(R.string.scan_result_not_found), vm.getErrorText().getValue());
        assertFalse(vm.getErrorRetryable().getValue());
        vm.onErrorAction();
        assertEquals(ResultEvent.CLOSE, events.get(0));
    }

    @Test
    public void load_errors() {
        repository.detailResult = Single.error(ApiException.of(40001, "该识别记录不存在或已删除"));
        vm.init(5, null);
        assertEquals(UiState.ERROR, vm.getUiState().getValue());
        assertEquals(texts.get(R.string.scan_result_not_found), vm.getErrorText().getValue());
        assertFalse(vm.getErrorRetryable().getValue());

        ResultViewModel other = new ResultViewModel(repository, Samples.GSON, texts);
        repository.detailResult = Single.error(ApiException.from(new UnknownHostException()));
        other.init(5, null);
        assertEquals(texts.get(R.string.scan_err_network), other.getErrorText().getValue());
        assertTrue(other.getErrorRetryable().getValue());

        repository.detailResult = Single.just(Samples.cucumber());
        other.onErrorAction();
        assertEquals(UiState.SUCCESS, other.getUiState().getValue());
    }

    @Test
    public void notSuccessRecord_goesToR02() {
        repository.detailResult = Single.just(Samples.failed());
        vm.init(77, null);
        assertEquals(ResultEvent.GO_ERROR, events.get(0));
        assertEquals(ErrorType.FAILED, vm.getErrorType());
        assertEquals("TIMEOUT", vm.getFailReason());
        assertEquals(77, vm.getRecordId());

        ResultViewModel blur = new ResultViewModel(repository, Samples.GSON, texts);
        RecognitionVO bad = Samples.failed();
        bad.setStatus("IMAGE_BAD");
        bad.setFailReason("IMAGE_BAD");
        blur.init(bad.getId(), Samples.GSON.toJson(bad));
        assertEquals(ErrorType.IMAGE_BAD, blur.getErrorType());
    }

    @Test
    public void cropAndHealthy_hideBox() {
        vm.init(2048, Samples.GSON.toJson(Samples.crop()));
        assertFalse(vm.getShowBox().getValue());
        assertEquals(TagItem.Style.CROP, vm.getTags().getValue().get(0).style);
        assertTrue(vm.getPhotoLabel().getValue().startsWith(texts.get(R.string.scan_mode_label_crop)));

        assertFalse(ResultViewModel.isShowBox("PEST", "健康"));
        assertTrue(ResultViewModel.isShowBox("PEST", "较重"));
        assertTrue(ResultViewModel.isShowBox("PEST", null));
        assertFalse(ResultViewModel.isShowBox("CROP", "中度"));
    }

    @Test
    public void retry_success_refreshesAndNotifies() {
        vm.init(1024, Samples.GSON.toJson(Samples.cucumber()));
        vm.markAnimated();
        RecognitionVO newer = Samples.cucumber();
        newer.setTopName("黄瓜靶斑病");
        newer.setConfidence(0.81);
        repository.retryResult = Single.just(newer);

        vm.retry();

        assertEquals(1, repository.retryCalls);
        assertEquals(Long.valueOf(1024), repository.retryIds.get(0));
        assertEquals(1, recordService.changedCount);
        assertEquals("黄瓜靶斑病", vm.getRecord().getValue().getTopName());
        assertEquals(81, (int) vm.getConfidenceProgress().getValue());
        assertFalse("重新识别成功后重播动画", vm.isAnimated());
        assertFalse(vm.getRetrying().getValue());
        assertEquals(texts.get(R.string.scan_result_retry_success), vm.getToast().getValue());
    }

    @Test
    public void retry_failures_keepOriginalResult() {
        RecognitionVO original = Samples.cucumber();
        vm.init(1024, Samples.GSON.toJson(original));

        assertRetryFailure(ApiException.of(42201), texts.get(R.string.scan_err_image_bad));
        assertRetryFailure(ApiException.of(50201), texts.get(R.string.scan_err_unavailable));
        assertRetryFailure(ApiException.of(50401), texts.get(R.string.scan_err_timeout));
        assertRetryFailure(ApiException.from(new UnknownHostException()), texts.get(R.string.scan_err_network));
        assertRetryFailure(ApiException.of(40001, "原照片已不存在，请重新拍摄"), "原照片已不存在，请重新拍摄");

        assertEquals(original, vm.getRecord().getValue());
        assertEquals(0, recordService.changedCount);
    }

    private void assertRetryFailure(Throwable error, String expectedToast) {
        repository.retryResult = Single.error(error);
        vm.retry();
        assertEquals(expectedToast, vm.getToast().getValue());
        assertFalse(vm.getRetrying().getValue());
    }

    @Test
    public void retry_inProgress_ignoresSecondTap_andBlocksAskAi() {
        vm.init(1024, Samples.GSON.toJson(Samples.cucumber()));
        repository.retryResult = Single.never();
        vm.retry();
        vm.retry();
        assertEquals(1, repository.retryCalls);
        assertTrue(vm.getRetrying().getValue());
        vm.askAi();
        assertTrue(events.isEmpty());
    }

    @Test
    public void askAi_andHistory() {
        vm.init(1024, Samples.GSON.toJson(Samples.cucumber()));
        vm.askAi();
        vm.openHistory();
        assertEquals(ResultEvent.ASK_AI, events.get(0));
        assertEquals(ResultEvent.OPEN_HISTORY, events.get(1));
        assertEquals(1024, vm.getRecordId());
    }
}
