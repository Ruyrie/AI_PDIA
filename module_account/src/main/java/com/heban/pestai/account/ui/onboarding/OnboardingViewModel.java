package com.heban.pestai.account.ui.onboarding;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A02 引导页：保存页码（旋转后停留在原页），判断是否最后一屏，发出结束事件。
 * 离开引导页（开始使用 / 跳过 / 已有账号？登录）时先写入引导标记再发事件；按返回键退出不写标记。
 */
public class OnboardingViewModel extends BaseViewModel {

    public static final long CLICK_WINDOW_MS = 500;

    public enum Event {
        NEXT_PAGE, GO_REGISTER, GO_LOGIN
    }

    private final AccountRepository repository;
    private final MutableLiveData<List<OnboardingPage>> pages = new MutableLiveData<>(OnboardingPage.defaults());
    private final int pageCount;
    private final MutableLiveData<Integer> currentPage = new MutableLiveData<>(0);
    private final LiveData<Boolean> isLast;
    private final SingleLiveEvent<Event> event = new SingleLiveEvent<>();
    private final PublishSubject<Boolean> primaryClicks = PublishSubject.create();

    @Inject
    public OnboardingViewModel(AccountRepository repository) {
        this.repository = repository;
        List<OnboardingPage> list = pages.getValue();
        this.pageCount = list == null ? 0 : list.size();
        this.isLast = Transformations.map(currentPage, page -> page != null && page >= pageCount - 1);
        add(primaryClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS)
                .subscribe(ignored -> handlePrimary()));
    }

    /** ViewPager2 滑动切换后回写；与当前值相同时不重复设置，避免与 setCurrentItem() 循环 */
    public void onPageSelected(int position) {
        Integer current = currentPage.getValue();
        if (current == null || current != position) {
            currentPage.setValue(position);
        }
    }

    /** 主按钮「下一步 / 开始使用」（500ms 防连点） */
    public void onPrimaryClick() {
        primaryClicks.onNext(Boolean.TRUE);
    }

    /** 右上角「跳过」 */
    public void onSkip() {
        leave(Event.GO_LOGIN);
    }

    /** 底部「已有账号？登录」 */
    public void onLogin() {
        leave(Event.GO_LOGIN);
    }

    private void handlePrimary() {
        if (Boolean.TRUE.equals(isLast.getValue()) || currentIndex() >= pageCount - 1) {
            leave(Event.GO_REGISTER);
        } else {
            event.setValue(Event.NEXT_PAGE);
        }
    }

    private void leave(Event target) {
        repository.setOnboardingDone();
        event.setValue(target);
    }

    private int currentIndex() {
        Integer value = currentPage.getValue();
        return value == null ? 0 : value;
    }

    public LiveData<List<OnboardingPage>> getPages() {
        return pages;
    }

    public int getPageCount() {
        return pageCount;
    }

    public MutableLiveData<Integer> getCurrentPage() {
        return currentPage;
    }

    public LiveData<Boolean> getIsLast() {
        return isLast;
    }

    public LiveData<Event> getEvent() {
        return event;
    }
}
