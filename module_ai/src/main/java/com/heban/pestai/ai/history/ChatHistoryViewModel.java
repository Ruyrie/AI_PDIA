package com.heban.pestai.ai.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.PageVO;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Q03 历史对话：分页（每页 20，滑到底部自动加载下一页）、下拉刷新、从 Q02 返回时静默刷新、删除、页面状态。
 * <ul>
 *     <li>只保存平铺的已加载会话（按 updated_at 倒序、按 session_id 去重），每次变化后重新生成分组列表行；</li>
 *     <li>加载更多的页码按「已加载条数 ÷ 20 + 1」推算（删除后服务器上的后续会话前移，重复的由去重过滤）；
 *     上一次加载更多一条也没有新增时改用「上次页码 + 1」，避免反复请求同一页；</li>
 *     <li>删除：确认后调用 DELETE ai/sessions/{id}，成功（含 40001：已不存在 / 不属于自己）后移除并提示「已删除」。</li>
 * </ul>
 * 所有方法都在主线程调用。
 */
public class ChatHistoryViewModel extends BaseViewModel {

    public static final int PAGE_SIZE = AiConstants.HISTORY_PAGE_SIZE;

    private final AiRepository repository;

    private final MutableLiveData<List<SessionRow>> rows = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.LOADING);
    private final MutableLiveData<String> errorText = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> refreshing = new MutableLiveData<>(false);
    /** 点击对话：打开 Q02 继续该对话（值为 session_id） */
    private final SingleLiveEvent<Long> openSession = new SingleLiveEvent<>();

    private final List<SessionItemVO> sessions = new ArrayList<>();
    /** 已提交删除、服务器尚未返回的会话，分页结果中过滤掉 */
    private final Set<Long> deletingIds = new HashSet<>();
    private boolean hasMore;
    private boolean loadingMore;
    private boolean loadMoreFailed;
    private boolean started;
    private int lastPage;
    private boolean lastPageAddedNothing;
    @Nullable
    private Disposable pageTask;
    private Clock clock = Clock.systemDefaultZone();

    @Inject
    public ChatHistoryViewModel(@NonNull AiRepository repository) {
        this.repository = repository;
    }

    // ==================== 对外数据 ====================

    public LiveData<List<SessionRow>> getRows() {
        return rows;
    }

    public LiveData<UiState> getUiState() {
        return uiState;
    }

    public LiveData<String> getErrorText() {
        return errorText;
    }

    public LiveData<Boolean> getRefreshing() {
        return refreshing;
    }

    public LiveData<Long> getOpenSession() {
        return openSession;
    }

    public boolean hasMore() {
        return hasMore;
    }

    // ==================== 加载 ====================

    /** 页面首次创建时调用；旋转屏幕重建不重复加载 */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        reload();
    }

    /** 清空列表、显示加载中、请求第 1 页（首次进入、错误页「重试」） */
    public void reload() {
        cancelPageTask();
        sessions.clear();
        hasMore = false;
        loadingMore = false;
        loadMoreFailed = false;
        lastPage = 0;
        lastPageAddedNothing = false;
        refreshing.setValue(false);
        errorText.setValue("");
        uiState.setValue(UiState.LOADING);
        publishRows();
        loadPage(true, false);
    }

    /** 下拉刷新：保留当前列表请求第 1 页，成功后整体替换；失败提示原因 */
    public void refresh() {
        if (uiState.getValue() != UiState.SUCCESS) {
            refreshing.setValue(false);
            return;
        }
        refreshing.setValue(true);
        loadPage(true, false);
    }

    /** 从 Q02 返回时：静默请求第 1 页并替换（会话可能有新消息而排到最前），失败不提示 */
    public void refreshSilently() {
        if (!started) {
            return;
        }
        if (uiState.getValue() == UiState.LOADING || Boolean.TRUE.equals(refreshing.getValue())) {
            return;
        }
        if (uiState.getValue() == UiState.ERROR) {
            reload();
            return;
        }
        loadPage(true, true);
    }

    /** 滑到接近底部时调用：有数据、还有下一页、没有进行中的请求、上次没有失败时才加载 */
    public void loadMore() {
        if (uiState.getValue() != UiState.SUCCESS || !hasMore || loadingMore || loadMoreFailed
                || Boolean.TRUE.equals(refreshing.getValue()) || isPageLoading()) {
            return;
        }
        loadingMore = true;
        publishRows();
        loadPage(false, false);
    }

    /** 点按「加载失败，点按重试」 */
    public void retryLoadMore() {
        if (!loadMoreFailed) {
            return;
        }
        loadMoreFailed = false;
        loadMore();
    }

    /** 加载更多的页码（纯计算，单元测试覆盖） */
    static int nextPage(int loadedCount, int pageSize, int lastPage, boolean lastPageAddedNothing) {
        int computed = Math.max(0, loadedCount) / pageSize + 1;
        return lastPageAddedNothing ? Math.max(computed, lastPage + 1) : computed;
    }

    private void loadPage(boolean reset, boolean silent) {
        cancelPageTask();
        if (reset) {
            loadingMore = false;
        }
        int page = reset ? 1 : nextPage(sessions.size(), PAGE_SIZE, lastPage, lastPageAddedNothing);
        Disposable task = repository.sessions(page, PAGE_SIZE)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> onPageLoaded(result, reset, page),
                        error -> onPageFailed(error, reset, silent));
        if (!task.isDisposed()) {
            pageTask = task;
            add(task);
        }
    }

    private void onPageLoaded(@NonNull PageVO<SessionItemVO> result, boolean reset, int page) {
        pageTask = null;
        if (reset) {
            sessions.clear();
            lastPage = 0;
            lastPageAddedNothing = false;
        }
        int added = merge(sessions, result.getList(), deletingIds);
        lastPage = page;
        lastPageAddedNothing = !reset && added == 0;
        hasMore = result.isHasMore();
        refreshing.setValue(false);
        loadingMore = false;
        loadMoreFailed = false;
        onSessionsChanged();
    }

    private void onPageFailed(@NonNull Throwable error, boolean reset, boolean silent) {
        pageTask = null;
        refreshing.setValue(false);
        loadingMore = false;
        String message = ApiException.messageOf(error);
        if (silent) {
            publishRows();
            return;
        }
        if (sessions.isEmpty()) {
            errorText.setValue(message);
            uiState.setValue(UiState.ERROR);
            publishRows();
        } else if (!reset) {
            loadMoreFailed = true;
            publishRows();
        } else {
            // 下拉刷新失败：保留原列表并提示（40101 的跳转登录由壳工程统一处理）
            publishRows();
            if (ApiException.codeOf(error) != ApiException.UNAUTHORIZED) {
                showToast(message);
            }
        }
    }

    /** 有会话 → SUCCESS；没有但还有下一页 → 自动加载下一页；否则 → EMPTY */
    private void onSessionsChanged() {
        boolean autoLoad = false;
        if (!sessions.isEmpty()) {
            uiState.setValue(UiState.SUCCESS);
        } else if (hasMore) {
            uiState.setValue(UiState.LOADING);
            autoLoad = !isPageLoading();
        } else {
            uiState.setValue(UiState.EMPTY);
        }
        publishRows();
        if (autoLoad) {
            loadingMore = true;
            loadPage(false, false);
        }
    }

    private void publishRows() {
        int footer = loadMoreFailed ? SessionRow.FOOTER_FAILED : loadingMore ? SessionRow.FOOTER_LOADING : 0;
        rows.setValue(SessionRowBuilder.build(sessions, LocalDateTime.now(clock), footer));
    }

    /** 按服务器顺序追加，session_id 已存在或正在删除的跳过；返回实际追加的条数 */
    static int merge(@NonNull List<SessionItemVO> target, @NonNull List<SessionItemVO> incoming,
                     @NonNull Set<Long> excluded) {
        Set<Long> seen = new HashSet<>();
        for (SessionItemVO vo : target) {
            seen.add(vo.getSessionId());
        }
        int added = 0;
        for (SessionItemVO vo : incoming) {
            if (vo == null || excluded.contains(vo.getSessionId()) || !seen.add(vo.getSessionId())) {
                continue;
            }
            target.add(vo);
            added++;
        }
        return added;
    }

    private void cancelPageTask() {
        if (pageTask != null) {
            disposables.remove(pageTask);
            pageTask = null;
        }
    }

    private boolean isPageLoading() {
        return pageTask != null && !pageTask.isDisposed();
    }

    // ==================== 点击与删除 ====================

    public void onRowClick(@NonNull SessionRow row) {
        if (row.getType() == SessionRow.TYPE_SESSION && indexOf(row.getSessionId()) >= 0) {
            openSession.setValue(row.getSessionId());
        }
    }

    /**
     * 确认删除后调用：请求 DELETE ai/sessions/{id}，成功后移除并提示「已删除」；
     * 40001（会话不存在或不属于当前用户）同样视为已删除；其他错误提示「删除失败，请稍后再试」。
     * 删除请求不随页面关闭取消。
     */
    public void delete(long sessionId) {
        if (indexOf(sessionId) < 0 || !deletingIds.add(sessionId)) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored 删除请求不随页面关闭取消
        repository.deleteSession(sessionId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> onDeleted(sessionId), error -> {
                    if (ApiException.codeOf(error) == ApiException.PARAM_ERROR) {
                        onDeleted(sessionId);
                    } else {
                        deletingIds.remove(sessionId);
                        showToast(AiConstants.TOAST_DELETE_FAILED);
                    }
                });
    }

    private void onDeleted(long sessionId) {
        deletingIds.remove(sessionId);
        int index = indexOf(sessionId);
        if (index >= 0) {
            sessions.remove(index);
            onSessionsChanged();
        }
        showToast(AiConstants.TOAST_SESSION_DELETED);
    }

    private int indexOf(long sessionId) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getSessionId() == sessionId) {
                return i;
            }
        }
        return -1;
    }

    @VisibleForTesting
    void setClock(@NonNull Clock clock) {
        this.clock = clock;
    }
}
