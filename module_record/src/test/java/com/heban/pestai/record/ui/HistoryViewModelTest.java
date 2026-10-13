package com.heban.pestai.record.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.PageVO;
import com.heban.pestai.record.TestRecords;
import com.heban.pestai.record.data.RecordRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;

/**
 * HistoryViewModel 关键逻辑（模块07 技术准备文档 2.10）：假 Repository 控制每次请求的返回时机，
 * io / 主线程调度器换成 trampoline，撤销计时用 TestScheduler 推进时间。
 */
public class HistoryViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private final TestScheduler timer = new TestScheduler();
    private FakeRepository repository;
    private FakeRecordService recordService;
    private HistoryViewModel vm;
    private long elapsed = 1_000_000L;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(s -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(s -> timer);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(s -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(s -> Schedulers.trampoline());
        repository = new FakeRepository();
        recordService = new FakeRecordService();
        vm = new HistoryViewModel(repository, recordService);
        setNow(TestRecords.NOW);
        vm.setElapsedRealtime(() -> elapsed);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    // ---------------------------------------------------------------- 首次加载与筛选

    @Test
    public void startLoadsFirstPageWithoutModeForAll() {
        vm.start(null);
        assertEquals(1, repository.calls.size());
        ListCall call = repository.calls.get(0);
        assertNull(call.mode);
        assertEquals(1, call.page);
        assertEquals(HistoryViewModel.PAGE_SIZE, call.size);
        assertEquals(UiState.LOADING, vm.getUiState().getValue());
        assertEquals(HistoryFilter.ALL, vm.getFilter().getValue());

        call.succeed(TestRecords.sample(), 6, false);

        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertEquals(Integer.valueOf(6), vm.getTotal().getValue());
        List<HistoryRow> rows = vm.getRows().getValue();
        assertEquals(10, rows.size());
        assertEquals("今天", rows.get(0).getTitle());
        HistoryRow footer = rows.get(9);
        assertEquals(HistoryRow.FOOTER_NORMAL, footer.getFooterState());
        assertEquals(6, footer.getTotal());
    }

    @Test
    public void startIsIgnoredAfterRotation() {
        vm.start(null);
        vm.start(Mode.PEST);
        assertEquals(1, repository.calls.size());
        assertEquals(HistoryFilter.ALL, vm.getFilter().getValue());
    }

    @Test
    public void entryModePreselectsFilter() {
        vm.start(Mode.PEST);
        assertEquals(HistoryFilter.PEST, vm.getFilter().getValue());
        assertEquals(Mode.PEST, repository.last().mode);
    }

    @Test
    public void unknownEntryModeFallsBackToAll() {
        vm.start("pest");
        assertEquals(HistoryFilter.ALL, vm.getFilter().getValue());
        assertNull(repository.last().mode);
    }

    @Test
    public void selectFilterReloadsWithMode() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);

        vm.selectFilter(HistoryFilter.CROP);
        assertEquals(2, repository.calls.size());
        assertEquals(Mode.CROP, repository.last().mode);
        assertEquals(1, repository.last().page);
        assertEquals(UiState.LOADING, vm.getUiState().getValue());
        assertTrue(vm.getRows().getValue().isEmpty());

        vm.selectFilter(HistoryFilter.CROP);
        assertEquals("相同筛选不重复请求", 2, repository.calls.size());

        vm.selectFilter(HistoryFilter.PEST);
        assertEquals(Mode.PEST, repository.last().mode);
    }

    @Test
    public void fastFilterSwitchShowsOnlyLatestResult() {
        vm.start(null);
        ListCall all = repository.last();
        vm.selectFilter(HistoryFilter.PEST);
        ListCall pest = repository.last();
        vm.selectFilter(HistoryFilter.CROP);
        ListCall crop = repository.last();

        assertFalse("旧请求已取消", all.subject.hasObservers());
        assertFalse("旧请求已取消", pest.subject.hasObservers());
        pest.succeed(TestRecords.sample(), 6, false);
        assertEquals(UiState.LOADING, vm.getUiState().getValue());

        RecordItemVO weed = TestRecords.sample().get(3);
        crop.succeed(Collections.singletonList(weed), 1, false);
        List<HistoryRow> rows = vm.getRows().getValue();
        assertEquals(3, rows.size());
        assertEquals("马唐（杂草）", rows.get(1).getTitle());
    }

    @Test
    public void emptyStates() {
        vm.start(null);
        repository.last().succeed(Collections.emptyList(), 0, false);
        assertEquals(UiState.EMPTY, vm.getUiState().getValue());
        assertFalse(vm.getEmptyFiltered().getValue());
        assertTrue(vm.getRows().getValue().isEmpty());

        vm.selectFilter(HistoryFilter.PEST);
        repository.last().succeed(Collections.emptyList(), 0, false);
        assertEquals(UiState.EMPTY, vm.getUiState().getValue());
        assertTrue("筛选后无记录显示「暂无该类记录」", vm.getEmptyFiltered().getValue());
    }

    @Test
    public void firstLoadFailureShowsErrorAndRetryReloads() {
        vm.start(null);
        repository.last().fail(ApiException.of(ApiException.NETWORK));
        assertEquals(UiState.ERROR, vm.getUiState().getValue());
        assertEquals("网络连接失败，请检查网络后重试", vm.getErrorText().getValue());

        vm.onStateAction();
        assertEquals(UiState.LOADING, vm.getUiState().getValue());
        assertEquals(2, repository.calls.size());
        assertEquals(1, repository.last().page);
        repository.last().succeed(TestRecords.sample(), 6, false);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
    }

    // ---------------------------------------------------------------- 分页

    @Test
    public void loadsThreePagesUntilNoMore() {
        vm.start(null);
        repository.last().succeed(TestRecords.range(45, 20), 45, true);

        vm.loadMore();
        assertEquals(2, repository.last().page);
        assertTrue(vm.getLoadingMore().getValue());
        assertEquals(HistoryRow.FOOTER_LOADING, footer().getFooterState());
        vm.loadMore();
        assertEquals("同一时间只有一个分页请求", 2, repository.calls.size());

        repository.last().succeed(TestRecords.range(25, 20), 45, true);
        assertFalse(vm.getLoadingMore().getValue());
        assertEquals(HistoryRow.FOOTER_NORMAL, footer().getFooterState());

        vm.loadMore();
        assertEquals(3, repository.last().page);
        repository.last().succeed(TestRecords.range(5, 5), 45, false);

        assertEquals(45, recordIds().size());
        assertEquals(45, footer().getTotal());
        vm.loadMore();
        assertEquals("has_more 为 false 后不再请求", 3, repository.calls.size());
    }

    @Test
    public void loadMoreDeduplicatesById() {
        vm.start(null);
        repository.last().succeed(TestRecords.range(45, 20), 45, true);
        vm.loadMore();
        // 两次请求之间顶部新增了 1 条，第 2 页第一条与第 1 页最后一条（26）重复
        repository.last().succeed(TestRecords.range(26, 20), 46, true);
        List<Long> ids = recordIds();
        assertEquals(39, ids.size());
        assertEquals(new HashSet<>(ids).size(), ids.size());
    }

    @Test
    public void loadMoreAfterDeleteHasNoGapAndNoDuplicate() {
        vm.start(null);
        repository.last().succeed(TestRecords.range(45, 20), 45, true);

        vm.delete(30);
        timer.advanceTimeBy(HistoryViewModel.UNDO_MS, TimeUnit.MILLISECONDS);
        repository.lastDelete().subject.onComplete();

        vm.loadMore();
        // 已加载 19 条：19 ÷ 20 + 1 = 1，服务器删除后第 1 页为 45..26（不含 30）共 20 条
        assertEquals(1, repository.last().page);
        List<RecordItemVO> serverPage1 = withoutId(TestRecords.range(45, 21), 30);
        repository.last().succeed(serverPage1, 44, true);
        assertEquals(20, recordIds().size());
        assertTrue(recordIds().contains(25L));

        vm.loadMore();
        assertEquals(2, repository.last().page);
        repository.last().succeed(withoutId(TestRecords.range(25, 21), 30).subList(1, 21), 44, true);
        List<Long> ids = recordIds();
        assertEquals(40, ids.size());
        assertEquals(new HashSet<>(ids).size(), ids.size());
        assertFalse(ids.contains(30L));
        for (long id = 45; id >= 6; id--) {
            if (id != 30) {
                assertTrue("缺少记录 " + id, ids.contains(id));
            }
        }
    }

    @Test
    public void loadMoreFailureShowsFooterAndRetry() {
        vm.start(null);
        repository.last().succeed(TestRecords.range(45, 20), 45, true);
        vm.loadMore();
        repository.last().fail(ApiException.of(ApiException.TIMEOUT));

        assertTrue(vm.getLoadMoreFailed().getValue());
        assertEquals(HistoryRow.FOOTER_FAILED, footer().getFooterState());
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());

        vm.loadMore();
        assertEquals("失败后不自动重试", 2, repository.calls.size());

        vm.retryLoadMore();
        assertEquals(3, repository.calls.size());
        assertEquals(2, repository.last().page);
        assertEquals(HistoryRow.FOOTER_LOADING, footer().getFooterState());
        repository.last().succeed(TestRecords.range(25, 20), 45, true);
        assertEquals(40, recordIds().size());
        assertFalse(vm.getLoadMoreFailed().getValue());
    }

    @Test
    public void pageSkipsAheadWhenNothingNew() {
        vm.start(null);
        repository.last().succeed(TestRecords.range(45, 20), 45, true);
        vm.delete(30);

        // 待撤销期间加载下一页：19 ÷ 20 + 1 = 1，服务器尚未删除，第 1 页全部是已加载 / 待撤销的记录
        vm.loadMore();
        assertEquals(1, repository.last().page);
        repository.last().succeed(TestRecords.range(45, 20), 45, true);
        assertFalse("待撤销的记录不会重新出现", recordIds().contains(30L));
        assertEquals(19, recordIds().size());
        assertEquals("待撤销的那条不计入总数", Integer.valueOf(44), vm.getTotal().getValue());

        vm.loadMore();
        assertEquals("去重后没有新增，页码顺延", 2, repository.last().page);
    }

    @Test
    public void autoLoadsNextPageWhenAllLoadedRecordsDeleted() {
        vm.start(null);
        repository.last().succeed(TestRecords.range(45, 1), 30, true);
        vm.delete(45);
        assertEquals("还有下一页时不显示空状态", UiState.LOADING, vm.getUiState().getValue());
        assertEquals(2, repository.calls.size());
        repository.last().succeed(TestRecords.range(44, 20), 29, true);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertEquals(20, recordIds().size());
    }

    // ---------------------------------------------------------------- 下拉刷新与静默刷新

    @Test
    public void refreshReplacesListAndFailureKeepsIt() {
        vm.start(null);
        repository.last().succeed(TestRecords.range(45, 20), 45, true);
        vm.loadMore();
        repository.last().succeed(TestRecords.range(25, 20), 45, true);
        assertEquals(40, recordIds().size());

        vm.refresh();
        assertTrue(vm.getRefreshing().getValue());
        assertEquals(1, repository.last().page);
        assertEquals("刷新时保留列表", UiState.SUCCESS, vm.getUiState().getValue());
        repository.last().succeed(TestRecords.range(46, 20), 46, true);
        assertFalse(vm.getRefreshing().getValue());
        assertEquals("刷新后整体替换第 1 页", 20, recordIds().size());
        assertEquals(Long.valueOf(46), recordIds().get(0));

        vm.refresh();
        repository.last().fail(ApiException.of(ApiException.NETWORK));
        assertFalse(vm.getRefreshing().getValue());
        assertEquals(20, recordIds().size());
        assertEquals("网络连接失败，请检查网络后重试", vm.getToast().getValue());
    }

    @Test
    public void refreshCommitsPendingDeleteFirst() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        vm.delete(1024);
        vm.refresh();
        assertEquals(1, repository.deletes.size());
        assertNull(vm.getPendingDelete().getValue());
        // 删除请求尚未返回时刷新结果仍包含它：按 deletingIds 过滤，避免「复活」
        repository.last().succeed(TestRecords.sample(), 6, false);
        assertFalse(recordIds().contains(1024L));
    }

    @Test
    public void externalChangeTriggersSilentRefreshOnResume() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);

        vm.refreshIfChanged();
        assertEquals("没有变化不刷新", 1, repository.calls.size());

        recordService.notifyChanged();
        assertTrue(vm.isDirty());
        vm.refreshIfChanged();
        assertEquals(2, repository.calls.size());
        assertEquals(1, repository.last().page);
        assertEquals("静默刷新不显示居中进度", UiState.SUCCESS, vm.getUiState().getValue());
        assertFalse(vm.getRefreshing().getValue());

        // 静默刷新失败：保留列表、不提示，下次回到页面再试
        repository.last().fail(ApiException.of(ApiException.NETWORK));
        assertNull(vm.getToast().getValue());
        assertTrue(vm.isDirty());

        vm.refreshIfChanged();
        List<RecordItemVO> updated = TestRecords.sample();
        // 失败记录在 R02 重试成功后变为成功记录
        updated.set(1, TestRecords.success(1023, Mode.PEST, "黄瓜靶斑病", 0.8, "轻度", updated.get(1).getCreatedAt()));
        repository.last().succeed(updated, 6, false);
        assertFalse(vm.isDirty());
        assertEquals(HistoryRow.TYPE_RECORD, vm.getRows().getValue().get(2).getType());
        assertEquals("黄瓜靶斑病", vm.getRows().getValue().get(2).getTitle());
    }

    @Test
    public void crossMidnightRebuildsHeaders() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        assertEquals("今天", vm.getRows().getValue().get(0).getTitle());

        setNow(TestRecords.NOW.plusDays(1));
        vm.refreshIfChanged();
        assertEquals("昨天", vm.getRows().getValue().get(0).getTitle());
        assertEquals(1, repository.calls.size());
    }

    // ---------------------------------------------------------------- 删除与撤销

    @Test
    public void deleteThenUndoRestoresWithoutRequest() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);

        vm.delete(1023);
        PendingDelete pending = vm.getPendingDelete().getValue();
        assertNotNull(pending);
        assertEquals(1023, pending.getRecord().getId());
        assertEquals(1, pending.getIndex());
        assertEquals(elapsed + HistoryViewModel.UNDO_MS, pending.getDeadline());
        assertEquals(HistoryViewModel.UNDO_MS, vm.remainingUndoMs(pending));
        assertFalse(recordIds().contains(1023L));
        assertEquals(Integer.valueOf(5), vm.getTotal().getValue());
        assertEquals(5, footer().getTotal());

        vm.undoDelete();
        assertNull(vm.getPendingDelete().getValue());
        assertEquals(Long.valueOf(1023), recordIds().get(1));
        assertEquals(Integer.valueOf(6), vm.getTotal().getValue());

        timer.advanceTimeBy(10, TimeUnit.SECONDS);
        assertTrue("撤销后不发送删除请求", repository.deletes.isEmpty());
    }

    @Test
    public void deleteLastInGroupRemovesHeader() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        vm.delete(1024);
        vm.delete(1023);
        List<HistoryRow> rows = vm.getRows().getValue();
        assertEquals("昨天", rows.get(0).getTitle());
        assertTrue(rows.get(1).isFirst());
    }

    @Test
    public void deleteCommitsAfterFourSecondsAndNotifies() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        vm.delete(1024);

        timer.advanceTimeBy(HistoryViewModel.UNDO_MS - 1, TimeUnit.MILLISECONDS);
        assertTrue(repository.deletes.isEmpty());
        timer.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        assertEquals(1, repository.deletes.size());
        assertEquals(1024, repository.lastDelete().id);
        assertNull(vm.getPendingDelete().getValue());

        repository.lastDelete().subject.onComplete();
        assertEquals("删除成功后通知首页、我的页", 1, recordService.notifyCount);
        assertFalse("本页自己的删除不标记 dirty", vm.isDirty());
        vm.refreshIfChanged();
        assertEquals(1, repository.calls.size());
        assertFalse(recordIds().contains(1024L));
    }

    @Test
    public void deleteFailureRestoresRecordAndShowsToast() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        vm.delete(1022);
        vm.flushPendingDelete();
        repository.lastDelete().subject.onError(ApiException.of(ApiException.NETWORK));

        assertEquals(Long.valueOf(1022), recordIds().get(2));
        assertEquals(Integer.valueOf(6), vm.getTotal().getValue());
        assertEquals(HistoryViewModel.MSG_DELETE_FAILED, vm.getToast().getValue());
        assertEquals(0, recordService.notifyCount);
    }

    @Test
    public void deleteNotFoundIsTreatedAsSuccess() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        vm.delete(1022);
        vm.flushPendingDelete();
        repository.lastDelete().subject.onError(ApiException.of(ApiException.PARAM_ERROR, "记录不存在"));

        assertFalse(recordIds().contains(1022L));
        assertNull(vm.getToast().getValue());
        assertEquals(1, recordService.notifyCount);
    }

    @Test
    public void deletingLastRecordShowsEmptyAndUndoRestores() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample().subList(0, 1), 1, false);
        vm.delete(1024);
        assertEquals(UiState.EMPTY, vm.getUiState().getValue());
        assertTrue(vm.getRows().getValue().isEmpty());

        vm.undoDelete();
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertEquals(1, recordIds().size());
    }

    @Test
    public void secondDeleteCommitsFirst() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        vm.delete(1024);
        vm.delete(1022);
        assertEquals(1, repository.deletes.size());
        assertEquals(1024, repository.lastDelete().id);
        assertEquals(1022, vm.getPendingDelete().getValue().getRecord().getId());

        // 第一条的计时已取消，第二条重新计时 4 秒
        timer.advanceTimeBy(HistoryViewModel.UNDO_MS, TimeUnit.MILLISECONDS);
        assertEquals(2, repository.deletes.size());
        assertEquals(1022, repository.lastDelete().id);
    }

    @Test
    public void selectFilterCommitsPendingDelete() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        vm.delete(1024);
        vm.selectFilter(HistoryFilter.PEST);
        assertEquals(1, repository.deletes.size());
        assertNull(vm.getPendingDelete().getValue());
    }

    @Test
    public void onClearedCommitsPendingDeleteAndRemovesListener() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        vm.delete(1024);
        vm.onCleared();
        assertEquals(1, repository.deletes.size());
        assertTrue(recordService.listeners.isEmpty());
        // 页面关闭后删除请求仍会完成并通知其他组件
        repository.lastDelete().subject.onComplete();
        assertEquals(1, recordService.notifyCount);
    }

    // ---------------------------------------------------------------- 点击与导航

    @Test
    public void rowClickOpensResultOrErrorPage() {
        vm.start(null);
        List<RecordItemVO> records = TestRecords.sample();
        records.add(TestRecords.failed(1018, Mode.CROP, RecognitionStatus.IMAGE_BAD, FailReason.IMAGE_BAD,
                TestRecords.at(2026, 10, 8, 10, 0)));
        repository.last().succeed(records, 7, false);
        List<HistoryRow> rows = vm.getRows().getValue();

        vm.onRowClick(rows.get(1));
        HistoryEvent result = vm.getEvent().getValue();
        assertEquals(HistoryEvent.OPEN_RESULT, result.getType());
        assertEquals(1024, result.getRecordId());

        vm.onRowClick(rows.get(2));
        HistoryEvent error = vm.getEvent().getValue();
        assertEquals(HistoryEvent.OPEN_ERROR, error.getType());
        assertEquals(1023, error.getRecordId());
        assertEquals(ErrorType.FAILED, error.getErrorType());
        assertEquals(Mode.PEST, error.getMode());
        assertEquals(FailReason.TIMEOUT, error.getRecord().getFailReason());

        vm.onRowClick(rows.get(9));
        HistoryEvent imageBad = vm.getEvent().getValue();
        assertEquals(HistoryEvent.OPEN_ERROR, imageBad.getType());
        assertEquals(ErrorType.IMAGE_BAD, imageBad.getErrorType());
        assertEquals(Mode.CROP, imageBad.getMode());
    }

    @Test
    public void clickOnDeletedRowIsIgnored() {
        vm.start(null);
        repository.last().succeed(TestRecords.sample(), 6, false);
        HistoryRow row = vm.getRows().getValue().get(1);
        vm.delete(row.getRecordId());
        vm.onRowClick(row);
        assertNull(vm.getEvent().getValue());
    }

    @Test
    public void openScanUsesFilterMode() {
        vm.start(null);
        vm.openScan();
        assertEquals(Mode.PEST, vm.getEvent().getValue().getMode());
        assertEquals(HistoryEvent.OPEN_SCAN, vm.getEvent().getValue().getType());

        vm.selectFilter(HistoryFilter.CROP);
        repository.last().succeed(Collections.emptyList(), 0, false);
        vm.onStateAction();
        assertEquals(HistoryEvent.OPEN_SCAN, vm.getEvent().getValue().getType());
        assertEquals(Mode.CROP, vm.getEvent().getValue().getMode());
    }

    // ---------------------------------------------------------------- 工具

    private void setNow(LocalDateTime now) {
        vm.setClock(Clock.fixed(now.atZone(TestRecords.ZONE).toInstant(), TestRecords.ZONE));
    }

    private HistoryRow footer() {
        List<HistoryRow> rows = vm.getRows().getValue();
        HistoryRow last = rows.get(rows.size() - 1);
        assertEquals(HistoryRow.TYPE_FOOTER, last.getType());
        return last;
    }

    private List<Long> recordIds() {
        List<Long> ids = new ArrayList<>();
        for (HistoryRow row : vm.getRows().getValue()) {
            if (row.isRecord()) {
                ids.add(row.getRecordId());
            }
        }
        return ids;
    }

    private static List<RecordItemVO> withoutId(List<RecordItemVO> list, long id) {
        List<RecordItemVO> result = new ArrayList<>();
        for (RecordItemVO item : list) {
            if (item.getId() != id) {
                result.add(item);
            }
        }
        return result;
    }

    private static final class ListCall {
        final String mode;
        final int page;
        final int size;
        final SingleSubject<PageVO<RecordItemVO>> subject = SingleSubject.create();

        ListCall(String mode, int page, int size) {
            this.mode = mode;
            this.page = page;
            this.size = size;
        }

        void succeed(List<RecordItemVO> list, int total, boolean hasMore) {
            subject.onSuccess(TestRecords.page(list, total, hasMore));
        }

        void fail(Throwable error) {
            subject.onError(error);
        }
    }

    private static final class DeleteCall {
        final long id;
        final CompletableSubject subject = CompletableSubject.create();

        DeleteCall(long id) {
            this.id = id;
        }
    }

    /** 假 Repository：记录每次请求，由测试决定何时、以什么结果返回 */
    private static final class FakeRepository extends RecordRepository {
        final List<ListCall> calls = new ArrayList<>();
        final List<DeleteCall> deletes = new ArrayList<>();

        FakeRepository() {
            super(null);
        }

        @NonNull
        @Override
        public Single<PageVO<RecordItemVO>> list(String mode, int page, int size) {
            ListCall call = new ListCall(mode, page, size);
            calls.add(call);
            return call.subject;
        }

        @NonNull
        @Override
        public Completable delete(long id) {
            DeleteCall call = new DeleteCall(id);
            deletes.add(call);
            return call.subject;
        }

        ListCall last() {
            return calls.get(calls.size() - 1);
        }

        DeleteCall lastDelete() {
            return deletes.get(deletes.size() - 1);
        }
    }

    /** 假 IRecordService：在调用线程同步回调监听者（与主线程调用 RecordServiceImpl 的行为一致） */
    private static final class FakeRecordService implements IRecordService {
        final Set<OnRecordChangedListener> listeners = new HashSet<>();
        int notifyCount;

        @Override
        public void notifyChanged() {
            notifyCount++;
            for (OnRecordChangedListener listener : new ArrayList<>(listeners)) {
                listener.onRecordChanged();
            }
        }

        @Override
        public void addOnChangedListener(@NonNull OnRecordChangedListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeOnChangedListener(@NonNull OnRecordChangedListener listener) {
            listeners.remove(listener);
        }

        @NonNull
        @Override
        public Single<List<RecordItemVO>> recent(int size) {
            return Single.never();
        }

        @NonNull
        @Override
        public Single<RecordItemVO> get(long id) {
            return Single.never();
        }
    }
}
