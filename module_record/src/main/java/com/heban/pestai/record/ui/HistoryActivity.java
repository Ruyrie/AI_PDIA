package com.heban.pestai.record.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.record.R;
import com.heban.pestai.record.databinding.ActivityHistoryBinding;
import com.heban.pestai.record.widget.SwipeItemLayout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * R03 识别记录 / R04 空状态（action：RouterPath.HISTORY，可带 EXTRA_MODE 预选筛选）。
 * <ul>
 *     <li>DataBinding 绑定 HistoryViewModel：筛选 Chip、列表 / 居中进度 / 空状态与错误态的切换、下拉刷新进度。</li>
 *     <li>列表：分页滚动监听（倒数第 3 行可见时加载下一页）、列表开始拖动时收起展开的行。</li>
 *     <li>删除后显示「已删除 1 条记录」+「撤销」Snackbar（按剩余时间，旋转后重新显示）；离开页面时提交待撤销的删除。</li>
 *     <li>导航事件：成功记录 → R01，失败记录 → R02，拍照 → S01，全部通过 RouterPath 隐式 Intent。</li>
 * </ul>
 */
public class HistoryActivity extends BaseActivity {

    private static final String TAG = "HistoryActivity";
    /** 最后可见行距末尾不足 3 行时加载下一页 */
    private static final int LOAD_MORE_THRESHOLD = 3;

    private ActivityHistoryBinding binding;
    private HistoryViewModel viewModel;
    private HistoryAdapter adapter;
    private LinearLayoutManager layoutManager;

    /** 记录点击与顶栏拍照共用的 500ms 防连点 */
    private final PublishSubject<Runnable> clicks = PublishSubject.create();
    @Nullable
    private Disposable clickTask;
    @Nullable
    private Snackbar undoSnackbar;
    @Nullable
    private HistoryFilter shownFilter;
    /** 筛选变化后第一次提交非空列表时回到顶部 */
    private boolean scrollToTopPending;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_history);
        binding.setLifecycleOwner(this);
        viewModel = obtainViewModel(HistoryViewModel.class);
        binding.setVm(viewModel);
        InsetsHelper.applySystemBars(binding.getRoot());
        observeToast(viewModel);

        clickTask = clicks
                .throttleFirst(RxClick.WINDOW_MS, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                .subscribe(Runnable::run);

        setupToolbar();
        setupChips();
        setupList();
        observeViewModel();

        viewModel.start(getIntent().getStringExtra(RouterPath.EXTRA_MODE));
    }

    private void setupToolbar() {
        binding.historyToolbar.setNavigationOnClickListener(v -> finish());
        binding.historyToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.record_action_scan) {
                clicks.onNext(viewModel::openScan);
                return true;
            }
            return false;
        });
    }

    private void setupChips() {
        binding.historyChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            HistoryFilter filter = RecordBindingAdapters.filterOfChip(checkedIds.get(0));
            if (filter != null && filter != viewModel.getFilter().getValue()) {
                SwipeItemLayout.closeOpened();
                viewModel.selectFilter(filter);
            }
        });
    }

    private void setupList() {
        adapter = new HistoryAdapter(new HistoryAdapter.Callback() {
            @Override
            public void onRecordClick(@NonNull HistoryRow row) {
                clicks.onNext(() -> viewModel.onRowClick(row));
            }

            @Override
            public void onDeleteClick(@NonNull HistoryRow row) {
                viewModel.delete(row.getRecordId());
            }

            @Override
            public void onFooterClick(@NonNull HistoryRow row) {
                if (row.getFooterState() == HistoryRow.FOOTER_FAILED) {
                    viewModel.retryLoadMore();
                }
            }
        });
        layoutManager = new LinearLayoutManager(this);
        RecyclerView list = binding.historyList;
        list.setLayoutManager(layoutManager);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    SwipeItemLayout.closeOpened();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    loadMoreIfNearEnd();
                }
            }
        });

        binding.historyRefresh.setColorSchemeColors(
                MaterialColors.getColor(binding.historyRefresh, androidx.appcompat.R.attr.colorPrimary));
        binding.historyRefresh.setOnRefreshListener(() -> {
            SwipeItemLayout.closeOpened();
            viewModel.refresh();
        });
    }

    private void observeViewModel() {
        viewModel.getFilter().observe(this, filter -> {
            if (shownFilter != null && shownFilter != filter) {
                scrollToTopPending = true;
            }
            shownFilter = filter;
        });
        viewModel.getRows().observe(this, this::submitRows);
        viewModel.getPendingDelete().observe(this, this::showUndo);
        viewModel.getEvent().observe(this, this::onEvent);
    }

    private void submitRows(@Nullable List<HistoryRow> rows) {
        adapter.submitList(rows, () -> {
            if (scrollToTopPending && rows != null && !rows.isEmpty()) {
                scrollToTopPending = false;
                binding.historyList.scrollToPosition(0);
            }
            // 列表不足一屏（或删除后接近底部）时也要加载下一页：等本次布局完成后再判断
            binding.historyList.post(this::loadMoreIfNearEnd);
        });
    }

    private void loadMoreIfNearEnd() {
        int count = adapter.getItemCount();
        if (count == 0) {
            return;
        }
        int last = layoutManager.findLastVisibleItemPosition();
        if (last != RecyclerView.NO_POSITION && last >= count - LOAD_MORE_THRESHOLD) {
            viewModel.loadMore();
        }
    }

    /** pendingDelete 非空：按剩余时间显示「已删除 1 条记录」+「撤销」；为空：关闭当前 Snackbar */
    private void showUndo(@Nullable PendingDelete pending) {
        dismissUndo();
        if (pending == null) {
            return;
        }
        long remaining = viewModel.remainingUndoMs(pending);
        if (remaining <= 0) {
            return;
        }
        View root = findViewById(android.R.id.content);
        Snackbar snackbar = Snackbar.make(root, R.string.record_deleted_one, (int) remaining)
                .setAction(R.string.record_undo, v -> viewModel.undoDelete())
                .setActionTextColor(MaterialColors.getColor(root,
                        com.google.android.material.R.attr.colorPrimaryContainer));
        undoSnackbar = snackbar;
        snackbar.show();
    }

    private void dismissUndo() {
        if (undoSnackbar != null) {
            undoSnackbar.dismiss();
            undoSnackbar = null;
        }
    }

    private void onEvent(@Nullable HistoryEvent event) {
        if (event == null) {
            return;
        }
        Intent intent;
        switch (event.getType()) {
            case HistoryEvent.OPEN_RESULT:
                intent = RouterPath.intent(this, RouterPath.SCAN_RESULT)
                        .putExtra(RouterPath.EXTRA_RECORD_ID, event.getRecordId());
                break;
            case HistoryEvent.OPEN_ERROR: {
                RecordItemVO record = event.getRecord();
                intent = RouterPath.intent(this, RouterPath.SCAN_ERROR)
                        .putExtra(RouterPath.EXTRA_RECORD_ID, event.getRecordId())
                        .putExtra(RouterPath.EXTRA_ERROR_TYPE, event.getErrorType())
                        .putExtra(RouterPath.EXTRA_FAIL_REASON, record == null ? null : record.getFailReason())
                        .putExtra(RouterPath.EXTRA_IMAGE_URL, record == null ? null : record.getImageUrl())
                        .putExtra(RouterPath.EXTRA_MODE, event.getMode());
                break;
            }
            case HistoryEvent.OPEN_SCAN:
                intent = RouterPath.intent(this, RouterPath.SCAN)
                        .putExtra(RouterPath.EXTRA_MODE, event.getMode());
                break;
            default:
                return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // 目标页面所在组件尚未集成时不崩溃
            Log.w(TAG, "未找到页面：" + intent.getAction(), e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 其他页面新增或重新识别过记录（dirty）则静默刷新；跨过零点则重建「今天」「昨天」
        viewModel.refreshIfChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开页面（返回、跳转、切到后台）立即提交待撤销的删除；旋转屏幕时不提交，计时在 ViewModel 中继续
        if (!isChangingConfigurations()) {
            viewModel.flushPendingDelete();
        }
    }

    @Override
    protected void onDestroy() {
        if (clickTask != null) {
            clickTask.dispose();
            clickTask = null;
        }
        dismissUndo();
        super.onDestroy();
    }
}
