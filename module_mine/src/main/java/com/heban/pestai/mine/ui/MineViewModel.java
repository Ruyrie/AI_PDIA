package com.heban.pestai.mine.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Pair;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.base.UiState;
import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.UserStatsVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.mine.data.AppVersion;
import com.heban.pestai.mine.data.MineRepository;
import com.heban.pestai.mine.data.RecoveryReminderStore;
import com.heban.pestai.mine.format.StatsFormatter;
import com.heban.pestai.mine.format.UserFormatter;
import com.heban.pestai.mine.util.RecoveryReminderRules;
import com.heban.pestai.network.ApiException;

import java.time.LocalDateTime;
import java.util.function.LongSupplier;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * M01 我的（模块09 技术准备文档 2.4「MineViewModel」、2.7.1、2.7.2）。
 * <ul>
 *     <li>直接持有接口返回的 UserVO、UserStatsVO，界面文字用 Transformations.map() 从 VO 派生。</li>
 *     <li>先显示 IUserService 缓存的资料，再用 Single.zip 并行请求 user/me 与 user/stats；资料失败时退回缓存。</li>
 *     <li>记录或资料变化只标记 dirty，页面再次可见时由 {@link #refreshIfNeeded()} 合并为一次刷新。</li>
 *     <li>资料区可点击：已登录用户进入 A06 编辑资料（保存后经 IUserService.userChanges() 刷新本页），游客进入登录页。</li>
 *     <li>新增：资料区下方「你还没有保存账号恢复码」提醒卡片，页面每次可见时由 {@link #refreshRecoveryReminder()}
 *     查询 GET user/recovery-code；已生成、游客、查询失败、7 天内关闭过（本机按用户 ID 记录）时不显示。</li>
 * </ul>
 */
public class MineViewModel extends BaseViewModel {

    private static final long NO_USER = 0L;

    private final MineRepository repository;
    private final IUserService userService;
    private final RecoveryReminderStore reminderStore;

    private final MutableLiveData<UserVO> user = new MutableLiveData<UserVO>(null);
    /** 初值显式为 null（「已设置」状态），派生文字立即显示「--」；未赋值的 LiveData 不会触发 map() */
    private final MutableLiveData<UserStatsVO> stats = new MutableLiveData<UserStatsVO>(null);
    private final MutableLiveData<Boolean> guest = new MutableLiveData<>(false);
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>();
    private final SingleLiveEvent<MineEvent> event = new SingleLiveEvent<>();
    /** 「未生成账号恢复码」提醒卡片 */
    private final MutableLiveData<Boolean> showRecoveryReminder = new MutableLiveData<>(false);

    private final LiveData<String> nameText;
    private final LiveData<String> avatarText;
    private final LiveData<String> phoneRegion;
    private final LiveData<Boolean> personAvatar;
    private final LiveData<String> totalText;
    private final LiveData<String> pestKindsText;
    private final LiveData<String> aiCountText;
    private final LiveData<String> recordCountText;
    private final LiveData<String> monthText;
    private final LiveData<String> lastRecordText;
    private final String versionText;

    /** 是否成功加载过（游客视为已加载） */
    private boolean loaded;
    /** 记录 / 资料已变化，下次可见时需要刷新；不需要界面观察，用普通字段 */
    private boolean dirty;
    private boolean loading;

    private LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private Disposable reminderRequest;
    /** 提醒卡片当前结果所属的用户；换了账号时先隐藏，旧结果作废 */
    private long reminderUserId = NO_USER;

    @Inject
    public MineViewModel(MineRepository repository, IUserService userService, AppVersion appVersion,
                         RecoveryReminderStore reminderStore) {
        this.repository = repository;
        this.userService = userService;
        this.reminderStore = reminderStore;
        this.versionText = "v" + appVersion.name();

        nameText = Transformations.map(user, UserFormatter::displayName);
        avatarText = Transformations.map(user, u -> u == null ? "" : UserFormatter.avatarText(u.getNickname()));
        phoneRegion = Transformations.map(user, UserFormatter::phoneRegion);
        totalText = Transformations.map(stats, StatsFormatter::total);
        pestKindsText = Transformations.map(stats, StatsFormatter::pestKinds);
        aiCountText = Transformations.map(stats, StatsFormatter::aiCount);
        recordCountText = Transformations.map(stats, StatsFormatter::recordCount);
        monthText = Transformations.map(stats, StatsFormatter::month);
        lastRecordText = Transformations.map(stats, s -> StatsFormatter.lastRecord(s, LocalDateTime.now()));
        personAvatar = Transformations.map(user, UserFormatter::needsPlaceholderAvatar);

        // 页面创建时立即显示本地缓存的资料，统计在 refresh() 完成前显示「--」
        boolean loggedIn = userService.isLoggedIn();
        guest.setValue(!loggedIn);
        user.setValue(loggedIn ? userService.currentUser() : null);
    }

    // ---------- 加载 ----------

    /** onResume / Tab 切回时调用：首次进入、数据过期或登录状态变化时才刷新，避免每次切 Tab 都请求 */
    public void refreshIfNeeded() {
        boolean guestNow = !userService.isLoggedIn();
        if (!loaded || dirty || guestNow != Boolean.TRUE.equals(guest.getValue())) {
            refresh();
        }
    }

    /** 游客直接显示「--」；登录用户先显示缓存，再并行请求资料与统计 */
    public void refresh() {
        if (!userService.isLoggedIn()) {
            guest.setValue(true);
            user.setValue(null);
            stats.setValue(null);
            hideRecoveryReminder();
            uiState.setValue(UiState.SUCCESS);
            loaded = true;
            dirty = false;
            return;
        }
        guest.setValue(false);
        UserVO cached = userService.currentUser();
        if (cached != null) {
            user.setValue(cached);
        }
        if (loading) {
            return;
        }
        // 本次刷新开始即消费 dirty：请求期间再收到变化通知会重新置为 true
        dirty = false;

        Single<UserVO> me = repository.me();
        if (cached != null) {
            // onErrorReturnItem() 不接受空值，没有缓存时让错误继续向下传递
            me = me.onErrorReturnItem(cached);
        }
        add(Single.zip(me, repository.stats(), Pair::new)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(d -> {
                    loading = true;
                    uiState.setValue(UiState.LOADING);
                })
                .doFinally(() -> loading = false)
                .subscribe(this::onLoaded, this::onLoadFailed));
    }

    private void onLoaded(@NonNull Pair<UserVO, UserStatsVO> result) {
        if (!userService.isLoggedIn()) {
            // 请求期间已退出登录
            return;
        }
        user.setValue(result.first);
        stats.setValue(result.second);
        loaded = true;
        uiState.setValue(UiState.SUCCESS);
    }

    private void onLoadFailed(@NonNull Throwable t) {
        // 统计保留上次的数据（首次失败仍为「--」），下次可见时重试
        dirty = true;
        uiState.setValue(UiState.ERROR);
        showToast(ApiException.messageOf(t));
    }

    /** IRecordService 记录变化回调：只标记，不立即请求 */
    public void markDirty() {
        dirty = true;
    }

    /** IUserService.userChanges() 发出新值（如模块02 修改资料） */
    public void onUserChanged(@Nullable UserVO changed) {
        if (changed == null || !userService.isLoggedIn() || changed.equals(user.getValue())) {
            // 退出登录 / 登录状态变化由 refreshIfNeeded() 比较游客状态处理
            return;
        }
        user.setValue(changed);
        if (!loading) {
            // 请求进行中时，进行中的 user/me 会带回最新资料，不再重复刷新
            dirty = true;
        }
    }

    // ---------- 账号恢复码提醒 ----------

    /**
     * onResume / Tab 切回时调用：游客或 7 天内关闭过直接隐藏（不请求）；否则 GET user/recovery-code，
     * 明确「未生成」才显示，已生成或查询失败都隐藏。请求进行中不重复发出，查询期间保持当前显示状态，避免闪烁。
     */
    public void refreshRecoveryReminder() {
        long userId = currentUserId();
        if (userId == NO_USER) {
            reminderUserId = NO_USER;
            hideRecoveryReminder();
            return;
        }
        if (userId != reminderUserId) {
            reminderUserId = userId;
            hideRecoveryReminder();
            if (reminderRequest != null) {
                // 上一个账号的请求作废，下面重新查询
                reminderRequest.dispose();
            }
        }
        if (RecoveryReminderRules.isSnoozed(reminderStore.dismissedAt(userId), clock.getAsLong())) {
            hideRecoveryReminder();
            return;
        }
        if (reminderRequest != null && !reminderRequest.isDisposed()) {
            return;
        }
        reminderRequest = repository.recoveryCode()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(vo -> applyRecoveryReminder(userId, vo),
                        t -> applyRecoveryReminder(userId, null));
        add(reminderRequest);
    }

    private void applyRecoveryReminder(long userId, @Nullable RecoveryCodeVO status) {
        if (currentUserId() != userId) {
            // 请求期间退出登录或切换了账号
            return;
        }
        boolean show = RecoveryReminderRules.shouldShow(status, reminderStore.dismissedAt(userId), clock.getAsLong());
        if (!Boolean.valueOf(show).equals(showRecoveryReminder.getValue())) {
            showRecoveryReminder.setValue(show);
        }
    }

    /** 提醒卡片右上角关闭：本机按用户 ID 记录关闭时刻，7 天内不再显示 */
    public void dismissRecoveryReminder() {
        long userId = currentUserId();
        if (userId != NO_USER) {
            reminderStore.setDismissedAt(userId, clock.getAsLong());
        }
        hideRecoveryReminder();
    }

    /** 提醒卡片「去生成」→ A12 账号恢复码 */
    public void onRecoveryReminderClick() {
        event.setValue(isGuestNow() ? MineEvent.NEED_LOGIN : MineEvent.GO_RECOVERY_CODE);
    }

    private void hideRecoveryReminder() {
        if (!Boolean.FALSE.equals(showRecoveryReminder.getValue())) {
            showRecoveryReminder.setValue(false);
        }
    }

    /** 当前登录用户的 ID；游客、没有资料或 ID 无效时为 {@link #NO_USER}（无法按用户记录关闭，不显示提醒） */
    private long currentUserId() {
        if (!userService.isLoggedIn()) {
            return NO_USER;
        }
        UserVO current = userService.currentUser();
        if (current == null) {
            current = user.getValue();
        }
        return current == null || current.getId() <= 0 ? NO_USER : current.getId();
    }

    @VisibleForTesting
    void setClock(@NonNull LongSupplier clock) {
        this.clock = clock;
    }

    // ---------- 点击 ----------

    /** 统计格：0 累计识别、1 识别出的病虫害 → R03（后者预选「病虫害」）；2 AI 问答 → AI 问答 Tab */
    public void onStatClick(int index) {
        if (index == 2) {
            event.setValue(MineEvent.GO_AI_TAB);
        } else if (isGuestNow()) {
            event.setValue(MineEvent.NEED_LOGIN);
        } else {
            event.setValue(index == 1 ? MineEvent.GO_HISTORY_PEST : MineEvent.GO_HISTORY);
        }
    }

    public void onScanClick() {
        event.setValue(isGuestNow() ? MineEvent.NEED_LOGIN : MineEvent.GO_SCAN);
    }

    public void onHistoryClick() {
        event.setValue(isGuestNow() ? MineEvent.NEED_LOGIN : MineEvent.GO_HISTORY);
    }

    public void onAskAiClick() {
        event.setValue(MineEvent.GO_AI_TAB);
    }

    public void onSettingsClick() {
        event.setValue(MineEvent.GO_SETTINGS);
    }

    public void onAboutClick() {
        event.setValue(MineEvent.GO_ABOUT);
    }

    public void onLoginClick() {
        event.setValue(MineEvent.GO_LOGIN);
    }

    /** 资料区（头像、昵称、手机号）：已登录 → A06 编辑资料；游客与「登录 / 注册」相同 → A03 登录 */
    public void onProfileClick() {
        event.setValue(isGuestNow() ? MineEvent.GO_LOGIN : MineEvent.GO_EDIT_PROFILE);
    }

    private boolean isGuestNow() {
        return !userService.isLoggedIn();
    }

    // ---------- getter ----------

    public LiveData<UserVO> getUser() {
        return user;
    }

    public LiveData<UserStatsVO> getStats() {
        return stats;
    }

    public LiveData<Boolean> getGuest() {
        return guest;
    }

    public LiveData<UiState> getUiState() {
        return uiState;
    }

    public LiveData<MineEvent> getEvent() {
        return event;
    }

    /** 资料区下方的「你还没有保存账号恢复码」提醒卡片 */
    public LiveData<Boolean> getShowRecoveryReminder() {
        return showRecoveryReminder;
    }

    public LiveData<String> getNameText() {
        return nameText;
    }

    /** 头像首字「老」（无头像图片时显示） */
    public LiveData<String> getAvatarText() {
        return avatarText;
    }

    public LiveData<String> getPhoneRegion() {
        return phoneRegion;
    }

    public LiveData<Boolean> getPersonAvatar() {
        return personAvatar;
    }

    public LiveData<String> getTotalText() {
        return totalText;
    }

    public LiveData<String> getPestKindsText() {
        return pestKindsText;
    }

    public LiveData<String> getAiCountText() {
        return aiCountText;
    }

    public LiveData<String> getRecordCountText() {
        return recordCountText;
    }

    public LiveData<String> getMonthText() {
        return monthText;
    }

    public LiveData<String> getLastRecordText() {
        return lastRecordText;
    }

    public String getVersionText() {
        return versionText;
    }

    /** 测试用：是否等待刷新 */
    boolean isDirty() {
        return dirty;
    }
}
