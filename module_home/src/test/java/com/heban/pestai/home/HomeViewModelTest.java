package com.heban.pestai.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.base.UiState;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.home.data.HomeRepository;
import com.heban.pestai.network.ApiException;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subjects.SingleSubject;

/**
 * 模块04 技术准备文档 2.10：HomeViewModel 关键逻辑（假 Repository，RxJava 调度器替换为 trampoline；
 * 防连点使用的 computation 调度器替换为 TestScheduler，以便控制 500ms 窗口）。
 */
public class HomeViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private TestScheduler clickScheduler;
    private FakeRepository repository;
    private FakeRecordService recordService;
    private MutableClock clock;
    private final List<HomeEvent> events = new ArrayList<>();
    private final List<String> toasts = new ArrayList<>();

    @Before
    public void setUp() {
        clickScheduler = new TestScheduler();
        RxJavaPlugins.setIoSchedulerHandler(s -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(s -> clickScheduler);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(c -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(s -> Schedulers.trampoline());

        repository = new FakeRepository();
        recordService = new FakeRecordService();
        clock = new MutableClock(LocalDateTime.of(2026, 10, 10, 14, 30));
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    private HomeViewModel create() {
        HomeViewModel vm = new HomeViewModel(repository, recordService, clock);
        vm.getEvent().observeForever(events::add);
        vm.getToast().observeForever(toasts::add);
        // MediatorLiveData 需要活跃观察者才会计算
        vm.getShowAll().observeForever(v -> {
        });
        return vm;
    }

    // ---------- 用户资料与问候语 ----------

    @Test
    public void cachedUser_showsGreetingImmediately_thenNetworkUpdates() {
        repository.cached = user("老李种菜");
        SingleSubject<UserVO> me = SingleSubject.create();
        repository.me = me;
        repository.recent = SingleSubject.create();

        HomeViewModel vm = create();

        assertEquals("10月10日 周六", vm.getDateText().getValue());
        assertEquals("下午好，老李", vm.getGreeting().getValue());
        assertEquals("老李种菜", vm.getUser().getValue().getNickname());
        assertEquals(Boolean.FALSE, vm.getGuest().getValue());

        me.onSuccess(user("张三丰收"));
        assertEquals("下午好，张三", vm.getGreeting().getValue());
        assertEquals("张三丰收", vm.getUser().getValue().getNickname());
    }

    @Test
    public void noCache_greetsNongyou_untilNetworkUser() {
        repository.cached = null;
        SingleSubject<UserVO> me = SingleSubject.create();
        repository.me = me;

        HomeViewModel vm = create();
        assertEquals("下午好，农友", vm.getGreeting().getValue());

        me.onSuccess(user("老李种菜"));
        assertEquals("下午好，老李", vm.getGreeting().getValue());
    }

    @Test
    public void userMeFailure_keepsCacheSilently() {
        repository.cached = user("老李种菜");
        repository.me = Single.error(ApiException.of(ApiException.NETWORK));
        repository.recent = Single.just(records(3));

        HomeViewModel vm = create();

        assertEquals("下午好，老李", vm.getGreeting().getValue());
        assertEquals("老李种菜", vm.getUser().getValue().getNickname());
        assertTrue(toasts.isEmpty());
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
    }

    @Test
    public void profileEditedElsewhere_updatesGreeting() {
        repository.cached = user("老李种菜");
        HomeViewModel vm = create();

        repository.userLive.setValue(user("王五"));
        assertEquals("下午好，王五", vm.getGreeting().getValue());
    }

    @Test
    public void updateClock_followsTimeAndDay() {
        repository.cached = user("老李种菜");
        HomeViewModel vm = create();

        clock.set(LocalDateTime.of(2026, 10, 10, 12, 59));
        vm.updateClock();
        assertEquals("中午好，老李", vm.getGreeting().getValue());

        clock.set(LocalDateTime.of(2026, 10, 10, 13, 0));
        vm.onVisible();
        assertEquals("下午好，老李", vm.getGreeting().getValue());

        clock.set(LocalDateTime.of(2026, 10, 12, 8, 0));
        vm.onVisible();
        assertEquals("10月12日 周一", vm.getDateText().getValue());
        assertEquals("上午好，老李", vm.getGreeting().getValue());
    }

    // ---------- 最近识别 ----------

    @Test
    public void recent_successShowsListAndAll() {
        repository.recent = Single.just(records(3));

        HomeViewModel vm = create();

        assertEquals(1, repository.recentCalls);
        assertEquals(HomeViewModel.RECENT_SIZE, repository.lastSize);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertEquals(3, vm.getRecentList().getValue().size());
        assertEquals(Boolean.TRUE, vm.getShowAll().getValue());
        assertEquals(Boolean.FALSE, vm.getRefreshing().getValue());
    }

    @Test
    public void recent_emptyShowsEmptyAndHidesAll() {
        repository.recent = Single.just(Collections.emptyList());

        HomeViewModel vm = create();

        assertEquals(UiState.EMPTY, vm.getUiState().getValue());
        assertEquals(Boolean.FALSE, vm.getShowAll().getValue());
    }

    @Test
    public void recent_loadingWhileFirstRequestPending() {
        repository.recent = SingleSubject.create();

        HomeViewModel vm = create();

        assertEquals(UiState.LOADING, vm.getUiState().getValue());
        assertEquals(Boolean.FALSE, vm.getShowAll().getValue());
    }

    @Test
    public void recent_errorWithoutData_showsErrorState() {
        repository.recent = Single.error(ApiException.of(ApiException.NETWORK));

        HomeViewModel vm = create();

        assertEquals(UiState.ERROR, vm.getUiState().getValue());
        assertEquals(HomeViewModel.NETWORK_ERROR_TEXT, vm.getErrorText().getValue());
        assertEquals(Boolean.FALSE, vm.getShowAll().getValue());
        assertTrue(toasts.isEmpty());

        // 服务器错误显示统一文案
        repository.recent = Single.error(ApiException.of(ApiException.SERVER_ERROR));
        vm.retry();
        assertEquals(UiState.ERROR, vm.getUiState().getValue());
        assertEquals("服务器开小差了，请稍后再试", vm.getErrorText().getValue());

        // 重试成功
        repository.recent = Single.just(records(2));
        vm.retry();
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertNull(vm.getErrorText().getValue());
    }

    @Test
    public void recent_errorWithData_keepsListAndToasts() {
        repository.recent = Single.just(records(3));
        HomeViewModel vm = create();
        List<RecordItemVO> before = vm.getRecentList().getValue();

        repository.me = Single.just(user("老李种菜"));
        repository.recent = Single.error(ApiException.of(ApiException.TIMEOUT));
        vm.onPullRefresh();

        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
        assertEquals(before, vm.getRecentList().getValue());
        assertEquals(Collections.singletonList("网络超时，请重试"), toasts);
        assertEquals(Boolean.FALSE, vm.getRefreshing().getValue());
    }

    @Test
    public void recent_unauthorized_doesNotShowError() {
        repository.recent = Single.error(ApiException.of(ApiException.UNAUTHORIZED));

        HomeViewModel vm = create();

        assertNotEquals(UiState.ERROR, vm.getUiState().getValue());
        assertTrue(toasts.isEmpty());
    }

    // ---------- 游客 ----------

    @Test
    public void guest_noRequestsAndLoginPrompts() {
        repository.guest = true;
        repository.cached = user("老李种菜");

        HomeViewModel vm = create();

        assertEquals(0, repository.meCalls);
        assertEquals(0, repository.recentCalls);
        assertEquals(Boolean.TRUE, vm.getGuest().getValue());
        assertNull(vm.getUser().getValue());
        assertEquals("你好，农友", vm.getGreeting().getValue());
        assertEquals(Boolean.FALSE, vm.getShowAll().getValue());

        vm.onPullRefresh();
        assertEquals(0, repository.recentCalls);
        assertEquals(Boolean.FALSE, vm.getRefreshing().getValue());

        vm.onScanClick();
        advanceClickWindow();
        vm.onAlbumClick();
        advanceClickWindow();
        vm.onModeClick(RouterPath.MODE_CROP);
        advanceClickWindow();
        vm.onHistoryClick();
        advanceClickWindow();
        vm.onLoginClick();
        assertEquals(5, events.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(HomeEvent.Type.NEED_LOGIN, events.get(i).type);
        }
        assertEquals(HomeEvent.Type.GO_LOGIN, events.get(4).type);
    }

    @Test
    public void loginStateChangedElsewhere_reloadsOnVisible() {
        repository.guest = true;
        HomeViewModel vm = create();
        assertEquals(0, repository.recentCalls);

        repository.guest = false;
        repository.cached = user("老李种菜");
        repository.recent = Single.just(records(1));
        vm.onVisible();

        assertEquals(Boolean.FALSE, vm.getGuest().getValue());
        assertEquals("下午好，老李", vm.getGreeting().getValue());
        assertEquals(1, repository.recentCalls);
        assertEquals(UiState.SUCCESS, vm.getUiState().getValue());
    }

    // ---------- 刷新 ----------

    @Test
    public void pullRefresh_indicatorStopsAfterBothRequests() {
        repository.recent = Single.just(records(3));
        HomeViewModel vm = create();

        SingleSubject<UserVO> me = SingleSubject.create();
        SingleSubject<List<RecordItemVO>> recent = SingleSubject.create();
        repository.me = me;
        repository.recent = recent;
        vm.onPullRefresh();
        assertEquals(Boolean.TRUE, vm.getRefreshing().getValue());

        recent.onSuccess(records(2));
        assertEquals(Boolean.TRUE, vm.getRefreshing().getValue());
        assertEquals(2, vm.getRecentList().getValue().size());

        me.onError(ApiException.of(ApiException.NETWORK));
        assertEquals(Boolean.FALSE, vm.getRefreshing().getValue());
        assertTrue(toasts.isEmpty());
    }

    @Test
    public void recordChanged_refreshesSilently_andListenerRemovedOnCleared() {
        repository.recent = Single.just(records(3));
        HomeViewModel vm = create();
        assertEquals(1, recordService.listeners.size());
        assertEquals(1, repository.recentCalls);

        repository.recent = Single.just(records(1));
        recordService.notifyChanged();

        assertEquals(2, repository.recentCalls);
        assertEquals(2, repository.meCalls);
        assertEquals(1, vm.getRecentList().getValue().size());
        assertEquals(Boolean.FALSE, vm.getRefreshing().getValue());

        vm.onCleared();
        assertTrue(recordService.listeners.isEmpty());
        assertFalse(repository.userLive.hasObservers());
    }

    @Test
    public void newRefresh_cancelsPrevious_andOwnsIndicator() {
        repository.recent = Single.just(records(3));
        HomeViewModel vm = create();

        SingleSubject<List<RecordItemVO>> first = SingleSubject.create();
        repository.recent = first;
        repository.me = Single.never();
        vm.onPullRefresh();
        assertTrue(first.hasObservers());

        // 下拉过程中收到记录变更：取消第一次刷新，指示器不闪断
        SingleSubject<List<RecordItemVO>> second = SingleSubject.create();
        SingleSubject<UserVO> secondMe = SingleSubject.create();
        repository.recent = second;
        repository.me = secondMe;
        recordService.notifyChanged();
        assertFalse(first.hasObservers());
        assertEquals(Boolean.TRUE, vm.getRefreshing().getValue());

        second.onSuccess(records(1));
        secondMe.onSuccess(user("老李种菜"));
        assertEquals(Boolean.FALSE, vm.getRefreshing().getValue());
        assertEquals(1, vm.getRecentList().getValue().size());
    }

    // ---------- 点击与跳转事件 ----------

    @Test
    public void scanAndModeClicks_carryMode() {
        HomeViewModel vm = create();

        vm.onScanClick();
        advanceClickWindow();
        vm.onModeClick(RouterPath.MODE_PEST);
        advanceClickWindow();
        vm.onModeClick(RouterPath.MODE_CROP);
        advanceClickWindow();
        vm.onAlbumClick();
        advanceClickWindow();
        vm.onHistoryClick();

        assertEquals(5, events.size());
        assertEquals(HomeEvent.Type.OPEN_SCAN, events.get(0).type);
        assertEquals(RouterPath.MODE_PEST, events.get(0).mode);
        assertEquals(RouterPath.MODE_PEST, events.get(1).mode);
        assertEquals(HomeEvent.Type.OPEN_SCAN, events.get(2).type);
        assertEquals(RouterPath.MODE_CROP, events.get(2).mode);
        assertEquals(HomeEvent.Type.PICK_PHOTO, events.get(3).type);
        assertEquals(HomeEvent.Type.OPEN_HISTORY, events.get(4).type);
    }

    @Test
    public void rapidClicks_onlyFirstWithin500ms() {
        HomeViewModel vm = create();

        vm.onScanClick();
        vm.onScanClick();
        vm.onModeClick(RouterPath.MODE_CROP);
        clickScheduler.advanceTimeBy(499, TimeUnit.MILLISECONDS);
        vm.onScanClick();
        assertEquals(1, events.size());

        clickScheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        vm.onModeClick(RouterPath.MODE_CROP);
        assertEquals(2, events.size());
        assertEquals(RouterPath.MODE_CROP, events.get(1).mode);
    }

    @Test
    public void recordClicks_routeByStatus() {
        HomeViewModel vm = create();

        RecordItemVO ok = record(1024, "SUCCESS");
        vm.onRecordClick(ok);
        advanceClickWindow();

        RecordItemVO failed = record(1030, "FAILED");
        failed.setFailReason("TIMEOUT");
        vm.onRecordClick(failed);
        advanceClickWindow();

        RecordItemVO imageBad = record(1031, "IMAGE_BAD");
        imageBad.setMode("CROP");
        imageBad.setFailReason("IMAGE_BAD");
        vm.onRecordClick(imageBad);
        advanceClickWindow();

        RecordItemVO unknown = record(1032, "SOMETHING");
        vm.onRecordClick(unknown);

        assertEquals(4, events.size());
        assertEquals(HomeEvent.Type.OPEN_RESULT, events.get(0).type);
        assertEquals(1024L, events.get(0).recordId);

        HomeEvent e1 = events.get(1);
        assertEquals(HomeEvent.Type.OPEN_ERROR, e1.type);
        assertEquals(1030L, e1.recordId);
        assertEquals("FAILED", e1.errorType);
        assertEquals("TIMEOUT", e1.failReason);
        assertEquals("PEST", e1.mode);
        assertEquals(failed.getImageUrl(), e1.imageUrl);

        HomeEvent e2 = events.get(2);
        assertEquals(HomeEvent.Type.OPEN_ERROR, e2.type);
        assertEquals("IMAGE_BAD", e2.errorType);
        assertEquals("IMAGE_BAD", e2.failReason);
        assertEquals("CROP", e2.mode);

        assertEquals(HomeEvent.Type.OPEN_ERROR, events.get(3).type);
        assertEquals("FAILED", events.get(3).errorType);
    }

    // ---------- 工具 ----------

    private void advanceClickWindow() {
        clickScheduler.advanceTimeBy(500, TimeUnit.MILLISECONDS);
    }

    private static UserVO user(String nickname) {
        UserVO user = new UserVO();
        user.setId(1001);
        user.setNickname(nickname);
        return user;
    }

    private static RecordItemVO record(long id, String status) {
        RecordItemVO item = new RecordItemVO();
        item.setId(id);
        item.setMode("PEST");
        item.setStatus(status);
        item.setImageUrl("http://10.0.2.2:8080/files/rec/20261010/" + id + ".jpg");
        item.setCreatedAt(new Date());
        if ("SUCCESS".equals(status)) {
            item.setTopName("黄瓜霜霉病");
            item.setConfidence(0.92);
            item.setSeverity("中度");
        }
        return item;
    }

    private static List<RecordItemVO> records(int count) {
        List<RecordItemVO> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(record(1000 + i, "SUCCESS"));
        }
        return list;
    }

    /** 假 Repository：不访问网络与 SharedPreferences */
    private static final class FakeRepository extends HomeRepository {

        boolean guest;
        UserVO cached;
        Single<UserVO> me = Single.never();
        Single<List<RecordItemVO>> recent = Single.never();
        final MutableLiveData<UserVO> userLive = new MutableLiveData<>();
        int meCalls;
        int recentCalls;
        int lastSize;

        FakeRepository() {
            super(null, null);
        }

        @Override
        public UserVO cachedUser() {
            return cached;
        }

        @Override
        public boolean isGuest() {
            return guest;
        }

        @NonNull
        @Override
        public LiveData<UserVO> userChanges() {
            return userLive;
        }

        @NonNull
        @Override
        public Single<UserVO> fetchMe() {
            meCalls++;
            return me;
        }

        @NonNull
        @Override
        public Single<List<RecordItemVO>> fetchRecent(int size) {
            recentCalls++;
            lastSize = size;
            return recent;
        }
    }

    private static final class FakeRecordService implements IRecordService {

        final List<OnRecordChangedListener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public void notifyChanged() {
            for (OnRecordChangedListener listener : listeners) {
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
            return Single.just(Collections.emptyList());
        }

        @NonNull
        @Override
        public Single<RecordItemVO> get(long id) {
            return Single.never();
        }
    }

    /** 可调整的时钟 */
    private static final class MutableClock extends Clock {

        private final ZoneId zone = ZoneId.systemDefault();
        private Instant instant;

        MutableClock(LocalDateTime time) {
            set(time);
        }

        void set(LocalDateTime time) {
            instant = time.atZone(zone).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
