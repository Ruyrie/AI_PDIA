package com.heban.pestai.record.ui;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.PageVO;
import com.heban.pestai.record.data.RecordRepository;
import com.heban.pestai.record.util.HistoryPaging;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * R03 / R04 识别记录（模块07 技术准备文档 2.4、2.7、2.8）。
 * <ul>
 *     <li>只保存平铺的已加载记录 records（按 created_at 倒序、按 id 去重，不含待撤销项），每次变化后重新生成 rows。</li>
 *     <li>筛选、分页（页码按已加载条数推算）、下拉刷新、日期分组、删除与 4 秒撤销、页面状态、导航事件。</li>
 *     <li>监听 IRecordService：其他组件引起的变化把 dirty 置为 true，onResume() 时静默刷新；
 *     本页删除成功后的 notifyChanged() 由 selfNotifying 标记忽略。</li>
 * </ul>
 * 所有方法都在主线程调用。
 */
public class HistoryViewModel extends BaseViewModel {

    public static final int PAGE_SIZE = 20;
    public static final long UNDO_MS = 4000L;

    static final String MSG_DELETE_FAILED = "删除失败，请稍后再试";

    private final RecordRepository repository;
    private final IRecordService recordService;

    private final MutableLiveData<HistoryFilter> filter = new MutableLiveData<>(HistoryFilter.ALL);
    private final MutableLiveData<List<HistoryRow>> rows = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Integer> total = new MutableLiveData<>(0);
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.LOADING);
    private final MutableLiveData<String> errorText = new MutableLiveData<>();
    private final MutableLiveData<Boolean> emptyFiltered = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> refreshing = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> loadingMore = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> loadMoreFailed = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> hasMore = new MutableLiveData<>(false);
    /** 普通 LiveData：旋转后重新订阅可按剩余时间重新显示 Snackbar */
    private final MutableLiveData<PendingDelete> pendingDelete = new MutableLiveData<>();
    private final SingleLiveEvent<HistoryEvent> event = new SingleLiveEvent<>();

    /** 已加载的记录（按 created_at 倒序、已去重、不含待撤销项） */
    private final List<RecordItemVO> records = new ArrayList<>();
    /** 已提交删除、服务器尚未返回的记录 id，分页结果中过滤掉，避免记录「复活」 */
    private final Set<Long> deletingIds = new HashSet<>();
    private int totalCount;
    private boolean dirty;
    private boolean selfNotifying;
    private boolean started;
    @Nullable
    private LocalDate builtDay;
    @Nullable
    private Disposable pageTask;
    /** 每发起一次分页请求加 1，用于识别同步完成的请求（单元测试中的 trampoline 调度器） */
    private int pageGeneration;
    @Nullable
    private Disposable undoTimer;
    private int lastPage;
    private boolean lastPageAddedNothing;

    private Clock clock = Clock.systemDefaultZone();
    private LongSupplier elapsedRealtime = SystemClock::elapsedRealtime;

    private final IRecordService.OnRecordChangedListener recordListener = () -> {
        if (!selfNotifying) {
            dirty = true;
        }
    };

    @Inject
    public HistoryViewModel(RecordRepository repository, IRecordService recordService) {
        this.repository = repository;
        this.recordService = recordService;
        recordService.addOnChangedListener(recordListener);
    }

    // ---------------------------------------------------------------- LiveData

    public LiveData<HistoryFilter> getFilter() {
        return filter;
    }

    public LiveData<List<HistoryRow>> getRows() {
        return rows;
    }

    public LiveData<Integer> getTotal() {
        return total;
    }

    public LiveData<UiState> getUiState() {
        return uiState;
    }

    public LiveData<String> getErrorText() {
        return errorText;
    }

    public LiveData<Boolean> getEmptyFiltered() {
        return emptyFiltered;
    }

    public LiveData<Boolean> getRefreshing() {
        return refreshing;
    }

    public LiveData<Boolean> getLoadingMore() {
        return loadingMore;
    }

    public LiveData<Boolean> getLoadMoreFailed() {
        return loadMoreFailed;
    }

    public LiveData<Boolean> getHasMore() {
        return hasMore;
    }

    public LiveData<PendingDelete> getPendingDelete() {
        return pendingDelete;
    }

    public LiveData<HistoryEvent> getEvent() {
        return event;
    }

    // ---------------------------------------------------------------- 加载

    /** 页面首次创建时调用；ViewModel 已初始化过（旋转屏幕）则直接返回 */
    public void start(@Nullable String mode) {
        if (started) {
            return;
        }
        started = true;
        filter.setValue(HistoryFilter.fromMode(mode));
        reload();
    }

    /** 切换筛选：与当前相同则忽略（包括 DataBinding 恢复选中状态触发的回调）；先提交待撤销的删除 */
    public void selectFilter(@NonNull HistoryFilter newFilter) {
        if (newFilter == filter.getValue()) {
            return;
        }
        flushPendingDelete();
        filter.setValue(newFilter);
        reload();
    }

    /** 清空列表，显示居中进度，请求第 1 页（首次进入、切换筛选、错误页「重试」） */
    public void reload() {
        cancelPageTask();
        records.clear();
        totalCount = 0;
        lastPage = 0;
        lastPageAddedNothing = false;
        hasMore.setValue(false);
        loadingMore.setValue(false);
        loadMoreFailed.setValue(false);
        refreshing.setValue(false);
        errorText.setValue(null);
        uiState.setValue(UiState.LOADING);
        publishRows();
        loadPage(true, false);
    }

    /** 下拉刷新：先提交待撤销的删除，保留当前列表请求第 1 页，成功后整体替换 */
    public void refresh() {
        flushPendingDelete();
        if (uiState.getValue() != UiState.SUCCESS) {
            refreshing.setValue(false);
            return;
        }
        refreshing.setValue(true);
        loadPage(true, false);
    }

    /** 滑到接近底部时调用：SUCCESS、还有下一页、没有进行中的请求、上次没有失败时才加载 */
    public void loadMore() {
        if (uiState.getValue() != UiState.SUCCESS || !isTrue(hasMore) || isTrue(loadingMore)
                || isTrue(refreshing) || isTrue(loadMoreFailed) || isPageLoading()) {
            return;
        }
        requestNextPage();
    }

    /** 点按「加载失败，点按重试」 */
    public void retryLoadMore() {
        if (!isTrue(loadMoreFailed)) {
            return;
        }
        loadMoreFailed.setValue(false);
        loadMore();
    }

    /** onResume() 时调用：其他页面改动过记录则静默刷新第 1 页；日期跨天则重建分组标题 */
    public void refreshIfChanged() {
        if (!started) {
            return;
        }
        if (dirty) {
            loadPage(true, true);
            return;
        }
        if (builtDay != null && !builtDay.equals(LocalDate.now(clock)) && !records.isEmpty()) {
            publishRows();
        }
    }

    private void requestNextPage() {
        loadingMore.setValue(true);
        publishRows();
        loadPage(false, false);
    }

    /**
     * 请求一页数据。reset 为 true 时请求第 1 页并整体替换；否则按已加载条数推算页码并追加。
     *
     * @param silent 失败时不提示（onResume 静默刷新）
     */
    private void loadPage(boolean reset, boolean silent) {
        cancelPageTask();
        if (reset) {
            // 下拉刷新 / 静默刷新取消了进行中的加载更多
            loadingMore.setValue(false);
        }
        HistoryFilter current = currentFilter();
        int page = reset ? 1 : HistoryPaging.nextPage(records.size(), PAGE_SIZE, lastPage, lastPageAddedNothing);
        int generation = ++pageGeneration;
        Disposable task = repository.list(current.mode, page, PAGE_SIZE)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> onPageLoaded(result, reset, page),
                        error -> onPageFailed(error, reset, silent));
        if (generation == pageGeneration && !task.isDisposed()) {
            pageTask = task;
            add(task);
        }
    }

    private void onPageLoaded(@NonNull PageVO<RecordItemVO> result, boolean reset, int page) {
        pageTask = null;
        if (reset) {
            records.clear();
            dirty = false;
            lastPage = 0;
            lastPageAddedNothing = false;
        }
        PendingDelete pending = pendingDelete.getValue();
        Set<Long> excluded = new HashSet<>(deletingIds);
        if (pending != null) {
            excluded.add(pending.getRecord().getId());
        }
        int added = HistoryPaging.merge(records, result.getList(), excluded);
        lastPage = page;
        lastPageAddedNothing = !reset && added == 0;
        // 待撤销的那条还没有提交服务器，服务器的 total 仍包含它
        totalCount = Math.max(records.size(), result.getTotal() - (pending != null ? 1 : 0));
        hasMore.setValue(result.isHasMore());
        refreshing.setValue(false);
        loadingMore.setValue(false);
        loadMoreFailed.setValue(false);
        onRecordsChanged();
    }

    private void onPageFailed(@NonNull Throwable error, boolean reset, boolean silent) {
        pageTask = null;
        refreshing.setValue(false);
        loadingMore.setValue(false);
        String message = ApiException.messageOf(error);
        if (silent && (!records.isEmpty() || uiState.getValue() == UiState.EMPTY)) {
            // 静默刷新失败：保留当前内容，dirty 仍为 true，下次回到页面再试
            publishRows();
            return;
        }
        if (records.isEmpty()) {
            // 首次加载 / 切换筛选 / 列表被删空后自动加载失败：整页错误态
            errorText.setValue(message);
            uiState.setValue(UiState.ERROR);
            publishRows();
        } else if (!reset) {
            loadMoreFailed.setValue(true);
            publishRows();
        } else {
            // 下拉刷新失败：保留原列表并提示
            publishRows();
            if (ApiException.codeOf(error) != ApiException.UNAUTHORIZED) {
                showToast(message);
            }
        }
    }

    /**
     * records 变化后（分页结果、删除、撤销、删除失败恢复）更新页面状态并重建列表行：
     * 有记录 → SUCCESS；没有记录但还有下一页 → 自动加载下一页（显示居中进度，不显示空状态）；否则 → EMPTY。
     */
    private void onRecordsChanged() {
        emptyFiltered.setValue(currentFilter() != HistoryFilter.ALL);
        boolean autoLoad = false;
        if (!records.isEmpty()) {
            uiState.setValue(UiState.SUCCESS);
        } else if (isTrue(hasMore)) {
            if (uiState.getValue() != UiState.LOADING) {
                uiState.setValue(UiState.LOADING);
            }
            autoLoad = !isPageLoading();
        } else {
            totalCount = 0;
            uiState.setValue(UiState.EMPTY);
        }
        publishRows();
        if (autoLoad) {
            requestNextPage();
        }
    }

    /** 由 records 生成列表行，并把 totalCount 同步到 total */
    private void publishRows() {
        LocalDate today = LocalDate.now(clock);
        builtDay = today;
        int footerState = isTrue(loadMoreFailed) ? HistoryRow.FOOTER_FAILED
                : isTrue(loadingMore) ? HistoryRow.FOOTER_LOADING : HistoryRow.FOOTER_NORMAL;
        totalCount = Math.max(totalCount, records.size());
        rows.setValue(HistoryRowBuilder.build(records, today, clock.getZone(), footerState, totalCount));
        total.setValue(totalCount);
    }

    private void cancelPageTask() {
        if (pageTask != null) {
            // 从 CompositeDisposable 移除的同时 dispose，旧请求的结果不会再回调
            disposables.remove(pageTask);
            pageTask = null;
        }
    }

    private boolean isPageLoading() {
        return pageTask != null && !pageTask.isDisposed();
    }

    // ---------------------------------------------------------------- 删除与撤销

    /** 点「删除」：先提交上一条待撤销的删除 → 本地移除 → 4 秒内可撤销 */
    public void delete(long id) {
        flushPendingDelete();
        int index = indexOf(id);
        if (index < 0) {
            return;
        }
        RecordItemVO removed = records.remove(index);
        totalCount = Math.max(0, totalCount - 1);
        pendingDelete.setValue(new PendingDelete(removed, index, elapsedRealtime.getAsLong() + UNDO_MS,
                currentFilter()));
        Disposable timer = Completable.timer(UNDO_MS, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::flushPendingDelete);
        undoTimer = timer;
        add(timer);
        onRecordsChanged();
    }

    /** 点「撤销」：取消计时，记录插回原位置，不请求接口 */
    public void undoDelete() {
        PendingDelete pending = pendingDelete.getValue();
        if (pending == null) {
            return;
        }
        cancelUndoTimer();
        pendingDelete.setValue(null);
        if (restoreAt(pending.getRecord(), pending.getIndex())) {
            onRecordsChanged();
        }
    }

    /**
     * 立即提交待撤销的删除（计时结束、离开页面、切换筛选、下拉刷新、再删一条时调用）。
     * 删除请求不加入 disposables：页面关闭后请求仍会发出并完成。
     */
    public void flushPendingDelete() {
        PendingDelete pending = pendingDelete.getValue();
        if (pending == null) {
            return;
        }
        cancelUndoTimer();
        pendingDelete.setValue(null);
        long id = pending.getRecord().getId();
        deletingIds.add(id);
        //noinspection ResultOfMethodCallIgnored
        repository.delete(id)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> onDeleted(id), error -> onDeleteFailed(pending, error));
    }

    /** 剩余撤销时间（毫秒），Activity 据此设置 Snackbar 的显示时长 */
    public long remainingUndoMs(@NonNull PendingDelete pending) {
        return pending.getDeadline() - elapsedRealtime.getAsLong();
    }

    private void onDeleted(long id) {
        deletingIds.remove(id);
        // 在主线程调用时 notifyChanged() 同步回调，回调执行时标记仍为 true，本页不会因自己的删除再刷新
        selfNotifying = true;
        try {
            recordService.notifyChanged();
        } finally {
            selfNotifying = false;
        }
    }

    private void onDeleteFailed(@NonNull PendingDelete pending, @NonNull Throwable error) {
        RecordItemVO record = pending.getRecord();
        deletingIds.remove(record.getId());
        if (ApiException.codeOf(error) == ApiException.PARAM_ERROR) {
            // 40001：记录不存在或不属于当前用户，结果等同于已删除
            onDeleted(record.getId());
            return;
        }
        boolean restored;
        if (pending.getFilter() == currentFilter()) {
            restored = restoreAt(record, pending.getIndex());
        } else {
            // 提交后已切换筛选：记录属于新筛选时按时间顺序插回
            restored = currentFilter().accepts(record) && restoreSorted(record);
        }
        if (restored) {
            onRecordsChanged();
        }
        showToast(MSG_DELETE_FAILED);
    }

    /** 插回原位置（原位置超出当前长度时插到末尾）；已在列表中则不处理 */
    private boolean restoreAt(@NonNull RecordItemVO record, int index) {
        if (indexOf(record.getId()) >= 0) {
            return false;
        }
        records.add(Math.max(0, Math.min(index, records.size())), record);
        totalCount++;
        return true;
    }

    /** 按 created_at 倒序插回；位置在已加载范围之后且还有下一页时不插入（之后加载更多会带回它） */
    private boolean restoreSorted(@NonNull RecordItemVO record) {
        if (indexOf(record.getId()) >= 0) {
            return false;
        }
        Date time = record.getCreatedAt();
        int index = records.size();
        for (int i = 0; i < records.size(); i++) {
            RecordItemVO other = records.get(i);
            if (time != null && other.getCreatedAt() != null && other.getCreatedAt().before(time)) {
                index = i;
                break;
            }
        }
        if (index == records.size() && isTrue(hasMore)) {
            return false;
        }
        records.add(index, record);
        totalCount++;
        return true;
    }

    private void cancelUndoTimer() {
        if (undoTimer != null) {
            disposables.remove(undoTimer);
            undoTimer = null;
        }
    }

    // ---------------------------------------------------------------- 点击与导航

    /** 成功记录 → OPEN_RESULT；失败记录 → OPEN_ERROR；找不到（例如刚被删除）则忽略 */
    public void onRowClick(@NonNull HistoryRow row) {
        if (!row.isRecord()) {
            return;
        }
        int index = indexOf(row.getRecordId());
        if (index < 0) {
            return;
        }
        RecordItemVO record = records.get(index);
        if (RecognitionStatus.SUCCESS.equals(record.getStatus())) {
            event.setValue(HistoryEvent.openResult(record.getId()));
        } else {
            event.setValue(HistoryEvent.openError(record));
        }
    }

    /** 顶栏拍照 /「去拍照识别」：「作物 / 杂草」筛选下预选 CROP，其余预选 PEST */
    public void openScan() {
        event.setValue(HistoryEvent.openScan(currentFilter().scanMode()));
    }

    /** 空状态 / 错误态的按钮：错误态「重试」，其余「去拍照识别」 */
    public void onStateAction() {
        if (uiState.getValue() == UiState.ERROR) {
            reload();
        } else {
            openScan();
        }
    }

    // ---------------------------------------------------------------- 其他

    @Override
    protected void onCleared() {
        flushPendingDelete();
        recordService.removeOnChangedListener(recordListener);
        super.onCleared();
    }

    @NonNull
    private HistoryFilter currentFilter() {
        HistoryFilter value = filter.getValue();
        return value == null ? HistoryFilter.ALL : value;
    }

    private int indexOf(long id) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTrue(@NonNull LiveData<Boolean> data) {
        return Boolean.TRUE.equals(data.getValue());
    }

    @VisibleForTesting
    void setClock(@NonNull Clock clock) {
        this.clock = clock;
    }

    @VisibleForTesting
    void setElapsedRealtime(@NonNull LongSupplier elapsedRealtime) {
        this.elapsedRealtime = elapsedRealtime;
    }

    @VisibleForTesting
    boolean isDirty() {
        return dirty;
    }
}
