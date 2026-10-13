package com.heban.pestai.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.home.data.HomeRepository;
import com.heban.pestai.home.util.GreetingHelper;
import com.heban.pestai.home.util.RecordFormatter;
import com.heban.pestai.network.ApiException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * H01 首页（模块04 技术准备文档 2.4、2.7、2.8）。
 * <ul>
 *     <li>缓存先行：先显示 user_json 中的 UserVO，再并行请求 user/me 与 recognitions/recent。</li>
 *     <li>user/me 失败只保留缓存不提示；最近识别失败时无数据显示错误态，有数据用 Snackbar 提示。</li>
 *     <li>通过 IRecordService 监听记录变化后静默刷新；新的刷新开始前取消上一次，只保留最后一次结果。</li>
 *     <li>点击事件经 PublishSubject + throttleFirst（500ms）防连点后写入 event。</li>
 * </ul>
 */
public class HomeViewModel extends BaseViewModel {

    /** 最近识别条数（recognitions/recent 的 size） */
    public static final int RECENT_SIZE = 3;
    /** 网络不可用时的提示（UI 设计文档 2.5） */
    public static final String NETWORK_ERROR_TEXT = "网络连接失败，请检查网络后重试";

    private final HomeRepository repository;
    @Nullable
    private final IRecordService recordService;
    private final Clock clock;

    private final MutableLiveData<UserVO> user = new MutableLiveData<>();
    private final MutableLiveData<String> dateText = new MutableLiveData<>();
    private final MutableLiveData<String> greeting = new MutableLiveData<>();
    private final MutableLiveData<Boolean> guest = new MutableLiveData<>(false);
    private final MutableLiveData<List<RecordItemVO>> recentList = new MutableLiveData<>();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.LOADING);
    private final MutableLiveData<String> errorText = new MutableLiveData<>();
    private final MediatorLiveData<Boolean> showAll = new MediatorLiveData<>();
    private final MutableLiveData<Boolean> refreshing = new MutableLiveData<>(false);
    private final SingleLiveEvent<HomeEvent> event = new SingleLiveEvent<>();

    private final PublishSubject<HomeEvent> clicks = PublishSubject.create();
    /** 记录变化：静默刷新（不显示下拉指示器）；同一个对象用于注册与移除 */
    private final IRecordService.OnRecordChangedListener recordListener = this::refresh;
    /** 我的页修改资料等写入 user_json 后同步更新问候语与头像 */
    private final Observer<UserVO> userObserver = this::applyUser;

    /** 每次刷新递增；doFinally 只在本次仍是最新刷新时收起指示器 */
    private int refreshSeq;
    @Nullable
    private Disposable currentRefresh;
    private int hour;

    @Inject
    public HomeViewModel(@NonNull HomeRepository repository) {
        this(repository, findRecordService(), Clock.systemDefaultZone());
    }

    @VisibleForTesting
    HomeViewModel(@NonNull HomeRepository repository, @Nullable IRecordService recordService, @NonNull Clock clock) {
        this.repository = repository;
        this.recordService = recordService;
        this.clock = clock;

        showAll.setValue(false);
        showAll.addSource(guest, ignored -> updateShowAll());
        showAll.addSource(uiState, ignored -> updateShowAll());
        add(clicks.throttleFirst(RxClick.WINDOW_MS, TimeUnit.MILLISECONDS, Schedulers.computation())
                .subscribe(event::setValue));

        // 1. 缓存先行：立即显示问候语与头像
        boolean isGuest = repository.isGuest();
        guest.setValue(isGuest);
        user.setValue(isGuest ? null : repository.cachedUser());
        // 2. 日期与问候语
        updateClock();
        repository.userChanges().observeForever(userObserver);
        // 3. 先注册记录变化监听，保证首次请求期间发生的变化不会漏掉
        if (recordService != null) {
            recordService.addOnChangedListener(recordListener);
        }
        // 4. 首次静默刷新
        refresh();
    }

    @Nullable
    private static IRecordService findRecordService() {
        try {
            return ServiceHub.get(IRecordService.class);
        } catch (IllegalStateException e) {
            // 组件单独运行、未注册 IRecordService 时只是没有自动刷新
            return null;
        }
    }

    // ---------- LiveData ----------

    public LiveData<UserVO> getUser() {
        return user;
    }

    public LiveData<String> getDateText() {
        return dateText;
    }

    public LiveData<String> getGreeting() {
        return greeting;
    }

    /** 是否游客：最近识别区显示登录提示，头像显示 person 图标 */
    public LiveData<Boolean> getGuest() {
        return guest;
    }

    public LiveData<List<RecordItemVO>> getRecentList() {
        return recentList;
    }

    public LiveData<UiState> getUiState() {
        return uiState;
    }

    public LiveData<String> getErrorText() {
        return errorText;
    }

    /** 「全部」是否可见：非游客且 uiState 为 SUCCESS */
    public LiveData<Boolean> getShowAll() {
        return showAll;
    }

    public LiveData<Boolean> getRefreshing() {
        return refreshing;
    }

    public LiveData<HomeEvent> getEvent() {
        return event;
    }

    // ---------- 时钟 ----------

    /** 重算日期与问候语；值不变时不触发观察者（跨天时 dateText 变化，页面据此重新绑定列表） */
    public void updateClock() {
        LocalDateTime now = LocalDateTime.now(clock);
        setIfChanged(dateText, GreetingHelper.dateText(now.toLocalDate()));
        hour = now.getHour();
        updateGreeting();
    }

    /** 页面重新可见（onResume、Tab 切回）：更新时钟；登录态在其他页面发生变化时按新身份重新加载 */
    public void onVisible() {
        updateClock();
        if (repository.isGuest() != isGuestNow()) {
            refresh();
        }
    }

    // ---------- 刷新 ----------

    /** 下拉刷新：显示指示器，重算时钟并刷新 */
    public void onPullRefresh() {
        refreshing.setValue(true);
        updateClock();
        refresh();
    }

    /** 错误态「重试」 */
    public void retry() {
        uiState.setValue(UiState.LOADING);
        refresh();
    }

    private void refresh() {
        int seq = ++refreshSeq;
        if (currentRefresh != null) {
            // remove() 同时取消上一次未完成的刷新，只保留最后一次结果
            disposables.remove(currentRefresh);
            currentRefresh = null;
        }
        boolean isGuest = repository.isGuest();
        applyGuest(isGuest);
        if (isGuest) {
            refreshing.setValue(false);
            return;
        }
        if (isEmpty(recentList.getValue())) {
            uiState.setValue(UiState.LOADING);
        }
        Completable me = repository.fetchMe()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSuccess(this::applyUser)
                .ignoreElement()
                .onErrorComplete();
        Completable recent = repository.fetchRecent(RECENT_SIZE)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSuccess(this::applyRecent)
                .doOnError(this::applyRecentError)
                .ignoreElement()
                .onErrorComplete();
        Disposable disposable = Completable.mergeArray(me, recent)
                .doFinally(() -> {
                    // 被新刷新取代而取消的旧刷新不改动指示器，由新刷新负责收起
                    if (seq == refreshSeq) {
                        refreshing.setValue(false);
                    }
                })
                .subscribe();
        if (!disposable.isDisposed() && seq == refreshSeq) {
            currentRefresh = disposable;
            add(disposable);
        }
    }

    private void applyGuest(boolean isGuest) {
        boolean wasGuest = isGuestNow();
        setIfChanged(guest, isGuest);
        if (isGuest) {
            setIfChanged(user, null);
            if (recentList.getValue() != null) {
                recentList.setValue(null);
            }
            errorText.setValue(null);
        } else if (wasGuest) {
            user.setValue(repository.cachedUser());
        }
        updateGreeting();
    }

    private void applyUser(@Nullable UserVO newUser) {
        if (isGuestNow()) {
            return;
        }
        setIfChanged(user, newUser);
        updateGreeting();
    }

    private void applyRecent(@NonNull List<RecordItemVO> list) {
        recentList.setValue(list);
        errorText.setValue(null);
        uiState.setValue(list.isEmpty() ? UiState.EMPTY : UiState.SUCCESS);
    }

    /** 40101 不处理（已统一跳转登录）；无数据显示错误态，有数据保留旧列表并用 Snackbar 提示 */
    private void applyRecentError(@NonNull Throwable throwable) {
        ApiException e = ApiException.from(throwable);
        if (e.getCode() == ApiException.UNAUTHORIZED) {
            return;
        }
        String message = e.getCode() == ApiException.NETWORK ? NETWORK_ERROR_TEXT : e.getMessage();
        if (isEmpty(recentList.getValue())) {
            errorText.setValue(message);
            uiState.setValue(UiState.ERROR);
        } else {
            showToast(message);
        }
    }

    private void updateGreeting() {
        UserVO current = user.getValue();
        setIfChanged(greeting, GreetingHelper.greeting(isGuestNow(),
                current == null ? null : current.getNickname(), hour));
    }

    private void updateShowAll() {
        setIfChanged(showAll, !isGuestNow() && uiState.getValue() == UiState.SUCCESS);
    }

    // ---------- 点击 ----------

    /** 「拍照识别」：默认病虫害模式 */
    public void onScanClick() {
        emit(requireLogin(HomeEvent.openScan(RouterPath.MODE_PEST)));
    }

    /** 「从相册选择」 */
    public void onAlbumClick() {
        emit(requireLogin(HomeEvent.of(HomeEvent.Type.PICK_PHOTO)));
    }

    /** 识别模式磁贴：MODE_PEST / MODE_CROP */
    public void onModeClick(@Nullable String mode) {
        String target = RouterPath.MODE_CROP.equals(mode) ? RouterPath.MODE_CROP : RouterPath.MODE_PEST;
        emit(requireLogin(HomeEvent.openScan(target)));
    }

    /** 顶栏 history 图标与「全部」 */
    public void onHistoryClick() {
        emit(requireLogin(HomeEvent.of(HomeEvent.Type.OPEN_HISTORY)));
    }

    /** 最近识别行：SUCCESS 进入 R01；IMAGE_BAD / FAILED（含未知状态）进入 R02 */
    public void onRecordClick(@Nullable RecordItemVO item) {
        if (item == null) {
            return;
        }
        emit(RecordFormatter.isSuccess(item) ? HomeEvent.openResult(item.getId()) : HomeEvent.openError(item));
    }

    /** 游客提示区「去登录」 */
    public void onLoginClick() {
        emit(HomeEvent.of(HomeEvent.Type.GO_LOGIN));
    }

    @NonNull
    private HomeEvent requireLogin(@NonNull HomeEvent event) {
        return isGuestNow() ? HomeEvent.of(HomeEvent.Type.NEED_LOGIN) : event;
    }

    private void emit(@NonNull HomeEvent homeEvent) {
        clicks.onNext(homeEvent);
    }

    // ---------- 工具 ----------

    private boolean isGuestNow() {
        return Boolean.TRUE.equals(guest.getValue());
    }

    private static boolean isEmpty(@Nullable List<?> list) {
        return list == null || list.isEmpty();
    }

    private static <T> void setIfChanged(@NonNull MutableLiveData<T> liveData, @Nullable T value) {
        if (!Objects.equals(liveData.getValue(), value)) {
            liveData.setValue(value);
        }
    }

    @Override
    protected void onCleared() {
        if (recordService != null) {
            recordService.removeOnChangedListener(recordListener);
        }
        repository.userChanges().removeObserver(userObserver);
        super.onCleared();
    }
}
