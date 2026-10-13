package com.heban.pestai.ai.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.ai.FakeAiRepository;
import com.heban.pestai.ai.RxSchedulersRule;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.PageVO;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;

/** Q03：分页、刷新、静默刷新、删除、页面状态 */
public class ChatHistoryViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxSchedulersRule rxRule = new RxSchedulersRule();

    private static final LocalDateTime NOW = SessionRowBuilderTest.NOW;

    private FakeAiRepository repo;
    private ChatHistoryViewModel vm;

    @Before
    public void setUp() {
        repo = new FakeAiRepository();
        vm = new ChatHistoryViewModel(repo);
        vm.setClock(Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    /** id 从 from 开始的 count 条会话，更新时间依次往前 1 小时 */
    private static List<SessionItemVO> sessions(long from, int count) {
        List<SessionItemVO> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = from + i;
            list.add(SessionRowBuilderTest.session(id, "对话 " + id, NOW.minusHours(id), null));
        }
        return list;
    }

    private static Single<PageVO<SessionItemVO>> page(List<SessionItemVO> list, boolean hasMore) {
        return Single.just(FakeAiRepository.page(list, list.size(), hasMore));
    }

    private List<SessionRow> rows() {
        return vm.getRows().getValue();
    }

    private int sessionRowCount() {
        int n = 0;
        for (SessionRow row : rows()) {
            if (row.getType() == SessionRow.TYPE_SESSION) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void start_loadsFirstPage_onlyOnce() {
        repo.pages = (page, size) -> {
            assertEquals(AiConstants.HISTORY_PAGE_SIZE, size);
            return page(sessions(1, 3), false);
        };
        vm.start();
        vm.start();

        assertEquals(Collections.singletonList(1), repo.pageCalls);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertEquals(3, sessionRowCount());
        assertEquals("今天", rows().get(0).getTitle());
    }

    @Test
    public void empty_showsEmptyState() {
        vm.start();
        assertEquals(UiState.EMPTY, vm.getUiState().getValue());
        assertTrue(rows().isEmpty());
    }

    @Test
    public void error_thenRetry() {
        repo.pages = (page, size) -> Single.error(ApiException.of(ApiException.NETWORK));
        vm.start();
        assertEquals(UiState.ERROR, vm.getUiState().getValue());
        assertEquals(ApiException.defaultMessage(ApiException.NETWORK), vm.getErrorText().getValue());

        repo.pages = (page, size) -> page(sessions(1, 2), false);
        vm.reload();
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertEquals(2, sessionRowCount());
    }

    @Test
    public void loadMore_appendsNextPage_withDedupe() {
        repo.pages = (page, size) -> page == 1 ? page(sessions(1, 20), true)
                // 第 2 页与第 1 页重叠 1 条（期间有会话更新）
                : page(sessions(20, 5), false);
        vm.start();
        assertTrue(vm.hasMore());
        vm.loadMore();

        assertEquals(Arrays.asList(1, 2), repo.pageCalls);
        assertEquals(24, sessionRowCount());
        assertFalse(vm.hasMore());
        vm.loadMore();
        assertEquals("没有下一页不再请求", 2, repo.pageCalls.size());
        SessionRow last = rows().get(rows().size() - 1);
        assertEquals("没有底部提示", SessionRow.TYPE_SESSION, last.getType());
    }

    @Test
    public void loadMore_showsLoadingFooter_thenFailureAndRetry() {
        SingleSubject<PageVO<SessionItemVO>> second = SingleSubject.create();
        repo.pages = (page, size) -> page == 1 ? page(sessions(1, 20), true) : second;
        vm.start();
        vm.loadMore();
        SessionRow footer = rows().get(rows().size() - 1);
        assertEquals(SessionRow.TYPE_FOOTER, footer.getType());
        assertEquals(SessionRow.FOOTER_LOADING, footer.getFooterState());

        second.onError(ApiException.of(ApiException.TIMEOUT));
        footer = rows().get(rows().size() - 1);
        assertEquals(SessionRow.FOOTER_FAILED, footer.getFooterState());
        assertEquals("加载更多失败不切换整页错误", UiState.SUCCESS, vm.getUiState().getValue());
        vm.loadMore();
        assertEquals("失败后不自动重试", 2, repo.pageCalls.size());

        repo.pages = (page, size) -> page(sessions(21, 3), false);
        vm.retryLoadMore();
        assertEquals(3, repo.pageCalls.size());
        assertEquals(23, sessionRowCount());
    }

    @Test
    public void refresh_replacesList_andFailureKeepsListWithToast() {
        repo.pages = (page, size) -> page(sessions(1, 3), false);
        vm.start();
        repo.pages = (page, size) -> page(sessions(10, 2), false);
        vm.refresh();
        assertEquals(Boolean.FALSE, vm.getRefreshing().getValue());
        assertEquals(2, sessionRowCount());
        assertEquals(10, rows().get(1).getSessionId());

        repo.pages = (page, size) -> Single.error(ApiException.of(ApiException.NETWORK));
        vm.refresh();
        assertEquals(2, sessionRowCount());
        assertEquals(ApiException.defaultMessage(ApiException.NETWORK), vm.getToast().getValue());
        assertEquals(Boolean.FALSE, vm.getRefreshing().getValue());
    }

    @Test
    public void refreshSilently_onReturnFromChat() {
        repo.pages = (page, size) -> page(sessions(1, 3), false);
        vm.start();
        // Q02 中继续提问后会话 3 排到最前
        SessionItemVO moved = SessionRowBuilderTest.session(3, "对话 3", NOW, null);
        repo.pages = (page, size) -> page(Arrays.asList(moved, sessions(1, 2).get(0), sessions(1, 2).get(1)), false);
        vm.refreshSilently();
        assertEquals(3, rows().get(1).getSessionId());

        repo.pages = (page, size) -> Single.error(ApiException.of(ApiException.NETWORK));
        vm.refreshSilently();
        assertEquals("静默刷新失败保留原列表", 3, sessionRowCount());
        assertNull("不提示", vm.getToast().getValue());
    }

    @Test
    public void delete_removesRow_andToasts() {
        repo.pages = (page, size) -> page(sessions(1, 3), false);
        vm.start();
        vm.delete(2);

        assertEquals(Collections.singletonList(2L), repo.deleteCalls);
        assertEquals(2, sessionRowCount());
        for (SessionRow row : rows()) {
            assertFalse(row.getSessionId() == 2 && row.getType() == SessionRow.TYPE_SESSION);
        }
        assertEquals("已删除", vm.getToast().getValue());
    }

    @Test
    public void delete_notOwned40001_treatedAsDeleted() {
        repo.pages = (page, size) -> page(sessions(1, 2), false);
        vm.start();
        repo.deleteResult = Completable.error(ApiException.of(ApiException.PARAM_ERROR));
        vm.delete(1);
        assertEquals(1, sessionRowCount());
        assertEquals(AiConstants.TOAST_SESSION_DELETED, vm.getToast().getValue());
    }

    @Test
    public void delete_failure_keepsRow() {
        repo.pages = (page, size) -> page(sessions(1, 2), false);
        vm.start();
        repo.deleteResult = Completable.error(ApiException.of(ApiException.NETWORK));
        vm.delete(1);
        assertEquals(2, sessionRowCount());
        assertEquals(AiConstants.TOAST_DELETE_FAILED, vm.getToast().getValue());

        repo.deleteResult = Completable.complete();
        vm.delete(1);
        assertEquals("失败后可以再删", 1, sessionRowCount());
    }

    @Test
    public void delete_lastOne_showsEmpty_orLoadsNextPage() {
        repo.pages = (page, size) -> page(sessions(1, 1), false);
        vm.start();
        vm.delete(1);
        assertEquals(UiState.EMPTY, vm.getUiState().getValue());

        ChatHistoryViewModel other = new ChatHistoryViewModel(repo);
        repo.pageCalls.clear();
        repo.pages = (page, size) -> page == 1 ? page(sessions(1, 1), true) : page(sessions(5, 2), false);
        other.start();
        other.delete(1);
        assertEquals("删空但还有下一页：自动加载", Arrays.asList(1, 1), repo.pageCalls);
    }

    @Test
    public void rowClick_opensSession() {
        repo.pages = (page, size) -> page(sessions(1, 2), false);
        vm.start();
        SessionRow header = rows().get(0);
        vm.onRowClick(header);
        assertNull(vm.getOpenSession().getValue());
        vm.onRowClick(rows().get(2));
        assertEquals(Long.valueOf(2), vm.getOpenSession().getValue());
    }

    @Test
    public void nextPage_byLoadedCount() {
        assertEquals(1, ChatHistoryViewModel.nextPage(0, 20, 0, false));
        assertEquals(2, ChatHistoryViewModel.nextPage(20, 20, 1, false));
        assertEquals("删除一条后回退一页，重复的由去重过滤", 1, ChatHistoryViewModel.nextPage(19, 20, 1, false));
        assertEquals("上次一条没加到：页码 + 1", 2, ChatHistoryViewModel.nextPage(19, 20, 1, true));
    }
}
