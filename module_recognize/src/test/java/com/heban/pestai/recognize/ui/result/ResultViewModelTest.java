package com.heban.pestai.recognize.ui.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.OtherItem;
import com.heban.pestai.recognize.model.ResultEvent;
import com.heban.pestai.recognize.model.TagItem;
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

public class ResultViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public TrampolineSchedulerRule schedulerRule = new TrampolineSchedulerRule();

    private final FakeTexts texts = new FakeTexts();
    private FakeRepository repository;
    private ResultViewModel vm;
    private final List<ResultEvent> events = new ArrayList<>();

    @Before
    public void setUp() {
        repository = new FakeRepository();
        vm = new ResultViewModel(repository, Samples.GSON, texts);
        vm.getEvent().observeForever(e -> events.add(e));
    }

    @Test
    public void init_withJson_showsWithoutRequest() {
        RecognitionVO vo = Samples.cucumber();
        vm.init(vo.getId(), Samples.GSON.toJson(vo), true);

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
        vm.init(1024, null, false);
        vm.init(1024, null, false);
        assertEquals(1, repository.detailCalls);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
    }

    @Test
    public void init_invalidJson_fallsBackToRequest() {
        repository.detailResult = Single.just(Samples.cucumber());
        vm.init(1024, "{not json", false);
        assertEquals(1, repository.detailCalls);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
    }

    @Test
    public void init_noIdNoJson_showsNotFound() {
        vm.init(-1, null, false);
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
        vm.init(5, null, false);
        assertEquals(UiState.ERROR, vm.getUiState().getValue());
        assertEquals(texts.get(R.string.scan_result_not_found), vm.getErrorText().getValue());
        assertFalse(vm.getErrorRetryable().getValue());

        ResultViewModel other = new ResultViewModel(repository, Samples.GSON, texts);
        repository.detailResult = Single.error(ApiException.from(new UnknownHostException()));
        other.init(5, null, false);
        assertEquals(texts.get(R.string.scan_err_network), other.getErrorText().getValue());
        assertTrue(other.getErrorRetryable().getValue());

        repository.detailResult = Single.just(Samples.cucumber());
        other.onErrorAction();
        assertEquals(UiState.SUCCESS, other.getUiState().getValue());
    }

    @Test
    public void notSuccessRecord_goesToR02() {
        repository.detailResult = Single.just(Samples.failed());
        vm.init(77, null, false);
        assertEquals(ResultEvent.GO_ERROR, events.get(0));
        assertEquals(ErrorType.FAILED, vm.getErrorType());
        assertEquals("TIMEOUT", vm.getFailReason());
        assertEquals(77, vm.getRecordId());

        ResultViewModel blur = new ResultViewModel(repository, Samples.GSON, texts);
        RecognitionVO bad = Samples.failed();
        bad.setStatus("IMAGE_BAD");
        bad.setFailReason("IMAGE_BAD");
        blur.init(bad.getId(), Samples.GSON.toJson(bad), false);
        assertEquals(ErrorType.IMAGE_BAD, blur.getErrorType());
    }

    @Test
    public void cropAndHealthy_hideBox() {
        vm.init(2048, Samples.GSON.toJson(Samples.crop()), false);
        assertFalse(vm.getShowBox().getValue());
        assertEquals(TagItem.Style.CROP, vm.getTags().getValue().get(0).style);
        assertTrue(vm.getPhotoLabel().getValue().startsWith(texts.get(R.string.scan_mode_label_crop)));

        assertFalse(ResultViewModel.isShowBox("PEST", "健康"));
        assertTrue(ResultViewModel.isShowBox("PEST", "较重"));
        assertTrue(ResultViewModel.isShowBox("PEST", null));
        assertFalse(ResultViewModel.isShowBox("CROP", "中度"));
    }

    @Test
    public void askAi_andHistory() {
        vm.init(1024, Samples.GSON.toJson(Samples.cucumber()), false);
        vm.askAi();
        vm.openHistory();
        assertEquals(ResultEvent.ASK_AI, events.get(0));
        assertEquals(ResultEvent.OPEN_HISTORY, events.get(1));
        assertEquals(1024, vm.getRecordId());
    }

    // ---------- 继续识别 ----------

    @Test
    public void continueScan_fromScan_backToCameraBelow() {
        vm.init(1024, Samples.GSON.toJson(Samples.cucumber()), true);
        vm.continueScan();
        assertEquals(ResultEvent.BACK_TO_SCAN, events.get(0));
        assertEquals("PEST", vm.getScanMode());
    }

    @Test
    public void continueScan_otherEntries_openCameraWithRecordMode() {
        repository.detailResult = Single.just(Samples.crop());
        vm.init(2048, null, false);
        vm.continueScan();
        assertEquals(ResultEvent.OPEN_SCAN, events.get(0));
        assertEquals("作物 / 杂草记录以作物模式打开相机", "CROP", vm.getScanMode());

        ResultViewModel pest = new ResultViewModel(repository, Samples.GSON, texts);
        List<ResultEvent> pestEvents = new ArrayList<>();
        pest.getEvent().observeForever(pestEvents::add);
        pest.init(1024, Samples.GSON.toJson(Samples.cucumber()), false);
        pest.continueScan();
        assertEquals(ResultEvent.OPEN_SCAN, pestEvents.get(0));
        assertEquals("PEST", pest.getScanMode());
    }

    @Test
    public void continueScan_unknownMode_defaultsToPest() {
        RecognitionVO vo = Samples.cucumber();
        vo.setMode(null);
        vm.init(vo.getId(), Samples.GSON.toJson(vo), false);
        assertEquals("PEST", vm.getScanMode());
    }

    @Test
    public void continueScan_ignoredUntilRecordShown() {
        repository.detailResult = Single.never();
        vm.init(1024, null, true);
        vm.continueScan();
        vm.askAi();
        assertTrue(events.isEmpty());
    }

    @Test
    public void continueScan_ignoredOnLoadError() {
        repository.detailResult = Single.error(ApiException.from(new UnknownHostException()));
        vm.init(1024, null, false);
        vm.continueScan();
        assertTrue(events.isEmpty());
    }

    // ---------- 其他可能：展开状态与显示规则 ----------

    @Test
    public void others_oldRecordWithoutExplanation_notExpandable_noHint() {
        vm.init(1024, Samples.GSON.toJson(Samples.cucumber()), false);
        List<OtherItem> items = vm.getOthers().getValue();
        assertEquals(2, items.size());
        assertEquals("黄瓜细菌性角斑病", items.get(0).getName());
        assertEquals(0.06, items.get(0).getConfidence(), 1e-9);
        for (OtherItem item : items) {
            assertFalse(item.isExpandable());
            assertFalse(item.isExpanded());
            assertFalse("没有可展开行时不为箭头占位", item.isReserveChevron());
        }
        assertFalse(vm.getOthersHintVisible().getValue());

        vm.toggleOther("黄瓜细菌性角斑病");
        assertFalse("没有判断依据的行不可展开", vm.getOthers().getValue().get(0).isExpanded());
    }

    @Test
    public void others_withExplanation_hintAndDifferenceLabel() {
        vm.init(1024, Samples.GSON.toJson(Samples.explained()), false);
        List<OtherItem> items = vm.getOthers().getValue();
        assertTrue(vm.getOthersHintVisible().getValue());

        OtherItem first = items.get(0);
        assertTrue(first.isExpandable());
        assertFalse("默认收起", first.isExpanded());
        assertEquals("病斑同样受叶脉限制呈多角形", first.getReason());
        assertEquals("角斑病叶背有白色菌脓，霜霉病是灰黑色霉层", first.getDifference());
        assertEquals(texts.get(R.string.scan_result_other_difference, "黄瓜霜霉病"), first.getDifferenceLabel());

        OtherItem second = items.get(1);
        assertFalse("reason / difference 都是空白", second.isExpandable());
        assertTrue("与可展开行对齐：箭头占位", second.isReserveChevron());
    }

    @Test
    public void others_toggle_expandsAndCollapses_keyedByName() {
        vm.init(1024, Samples.GSON.toJson(Samples.explained()), false);

        vm.toggleOther("黄瓜细菌性角斑病");
        assertTrue(vm.getOthers().getValue().get(0).isExpanded());
        assertFalse(vm.getOthers().getValue().get(1).isExpanded());

        vm.toggleOther("缺镁症（生理性）");
        vm.toggleOther("不存在的名称");
        vm.toggleOther(null);
        assertEquals(Arrays.asList(true, false, false), expandedFlags());

        vm.toggleOther("黄瓜蔓枯病");
        assertEquals(Arrays.asList(true, false, true), expandedFlags());

        vm.toggleOther("黄瓜细菌性角斑病");
        assertEquals(Arrays.asList(false, false, true), expandedFlags());
    }

    @Test
    public void others_expandState_survivesRotation() {
        String json = Samples.GSON.toJson(Samples.explained());
        vm.init(1024, json, true);
        vm.toggleOther("黄瓜蔓枯病");
        List<OtherItem> before = vm.getOthers().getValue();

        // 旋转后页面重新调用 init（不生效），重新订阅时拿到同一份展开状态
        vm.init(1024, json, true);
        assertEquals(before, vm.getOthers().getValue());
        assertEquals(Arrays.asList(false, false, true), expandedFlags());
    }

    @Test
    public void others_blankTopName_usesDefaultDifferenceLabel() {
        RecognitionVO vo = Samples.explained();
        vo.setTopName("  ");
        vm.init(vo.getId(), Samples.GSON.toJson(vo), false);
        assertEquals(texts.get(R.string.scan_result_other_difference_default),
                vm.getOthers().getValue().get(0).getDifferenceLabel());
    }

    @Test
    public void others_nullList_empty() {
        RecognitionVO vo = Samples.cucumber();
        vo.setOthers(null);
        vm.init(vo.getId(), Samples.GSON.toJson(vo), false);
        assertTrue(vm.getOthers().getValue().isEmpty());
        assertFalse(vm.getOthersHintVisible().getValue());
    }

    @Test
    public void others_onlyDifference_isExpandable() {
        RecognitionVO vo = Samples.cucumber();
        vo.setOthers(Arrays.asList(Samples.candidate("甲", 0.05, null, "区别"),
                Samples.candidate("乙", 0.03, "依据", "")));
        vm.init(vo.getId(), Samples.GSON.toJson(vo), false);
        OtherItem a = vm.getOthers().getValue().get(0);
        OtherItem b = vm.getOthers().getValue().get(1);
        assertTrue(a.isExpandable());
        assertNull(a.getReason());
        assertTrue(b.isExpandable());
        assertNull("空白的一段不显示", b.getDifference());
    }

    private List<Boolean> expandedFlags() {
        List<Boolean> flags = new ArrayList<>();
        for (OtherItem item : vm.getOthers().getValue()) {
            flags.add(item.isExpanded());
        }
        return flags;
    }
}
