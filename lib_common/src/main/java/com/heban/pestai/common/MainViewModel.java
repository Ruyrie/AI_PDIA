package com.heban.pestai.common;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

/**
 * 主页面 Tab 状态。以 Activity 为作用域，MainActivity 与 HomeFragment、AskAiFragment、MineFragment 共享同一个实例：
 * Fragment 调用 selectTab(TAB_AI) 等方法切换 Tab，MainActivity 观察 currentTab 后切换 Fragment，
 * 业务组件因此不需要依赖壳工程 app。
 *
 * <p>Fragment 中获取：{@code obtainActivityViewModel(MainViewModel.class)}（BaseFragment）。</p>
 */
public class MainViewModel extends ViewModel {

    public static final int TAB_HOME = 0;
    public static final int TAB_AI = 1;
    public static final int TAB_MINE = 2;

    private final MutableLiveData<Integer> currentTab = new MutableLiveData<>(TAB_HOME);

    @Inject
    public MainViewModel() {
    }

    public LiveData<Integer> getCurrentTab() {
        return currentTab;
    }

    public void selectTab(int tab) {
        if (tab < TAB_HOME || tab > TAB_MINE) {
            return;
        }
        Integer current = currentTab.getValue();
        if (current == null || current != tab) {
            currentTab.setValue(tab);
        }
    }
}
