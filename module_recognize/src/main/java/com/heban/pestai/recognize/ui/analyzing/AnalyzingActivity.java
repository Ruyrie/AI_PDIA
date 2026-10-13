package com.heban.pestai.recognize.ui.analyzing;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.UiState;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.databinding.ActivityAnalyzingBinding;
import com.heban.pestai.recognize.model.AnalyzeEvent;
import com.heban.pestai.recognize.service.RecognizeService;
import com.heban.pestai.recognize.service.RecognizeTask;
import com.heban.pestai.recognize.ui.scan.ScanActivity;
import com.heban.pestai.recognize.util.PhotoLoader;

/**
 * S02 识别中。首次进入时 startService()（旋转重建不重复上传），每次 onStart 绑定、onStop 解绑；
 * 按 Home 键离开时任务在 Service 中继续，回来绑定后立即回放最新进度。
 */
public class AnalyzingActivity extends BaseActivity {

    /** 扫描线一个来回 2.4 秒 */
    private static final long SCAN_CYCLE_MS = 2400;
    private static final float SCAN_TOP = 0.08f;
    private static final float SCAN_BOTTOM = 0.88f;

    private ActivityAnalyzingBinding binding;
    private AnalyzingViewModel viewModel;
    private boolean bound;
    @Nullable
    private ValueAnimator scanAnimator;
    @Nullable
    private AlertDialog confirmDialog;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof RecognizeTask) {
                viewModel.attach((RecognizeTask) service);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            viewModel.detach();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_analyzing);
        viewModel = obtainViewModel(AnalyzingViewModel.class);
        String imageUri = getIntent().getStringExtra(RouterPath.EXTRA_IMAGE_URI);
        String mode = getIntent().getStringExtra(RouterPath.EXTRA_MODE);
        viewModel.init(imageUri, mode);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        InsetsHelper.applySystemBars(binding.analyzingRoot);
        observeToast(viewModel);

        // 显示原图（不必等压缩完成）；本地临时照片不写磁盘缓存
        PhotoLoader.load(binding.analyzingPhoto, binding.analyzingPhotoState, null, viewModel.getImageUri(), false);

        if (savedInstanceState == null && !viewModel.isFinished()) {
            Intent service = new Intent(this, RecognizeService.class)
                    .putExtra(RouterPath.EXTRA_IMAGE_URI, imageUri)
                    .putExtra(RouterPath.EXTRA_MODE, mode);
            startService(service);
        }

        binding.analyzingToolbar.setNavigationOnClickListener(v -> viewModel.onBackPressed());
        // targetSdk 36 在 Android 16 上不再调用 onBackPressed()，改用 OnBackPressedDispatcher
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                viewModel.onBackPressed();
            }
        });

        viewModel.getUiState().observe(this, this::updateScanLine);
        viewModel.getEvent().observe(this, this::onEvent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!viewModel.isFinished()) {
            bound = bindService(new Intent(this, RecognizeService.class), connection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        viewModel.detach();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onStop();
    }

    private void onEvent(@Nullable AnalyzeEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type) {
            case GO_RESULT:
                startActivity(RouterPath.intent(this, RouterPath.SCAN_RESULT)
                        .putExtra(RouterPath.EXTRA_RECORD_ID, event.recordId)
                        .putExtra(RouterPath.EXTRA_RECORD_JSON, event.recordJson));
                finish();
                break;
            case GO_ERROR:
                startActivity(RouterPath.intent(this, RouterPath.SCAN_ERROR)
                        .putExtra(RouterPath.EXTRA_ERROR_TYPE, event.errorType)
                        .putExtra(RouterPath.EXTRA_FAIL_REASON, event.failReason)
                        .putExtra(RouterPath.EXTRA_RECORD_ID, RouterPath.NO_RECORD)
                        .putExtra(RouterPath.EXTRA_IMAGE_URI, viewModel.getImageUri())
                        .putExtra(RouterPath.EXTRA_MODE, viewModel.getRecognizeMode().value()));
                finish();
                break;
            case CANCELED:
                // 取消时 Service 可能尚未连接：解绑后由 stopService 结束
                stopService(new Intent(this, RecognizeService.class));
                setResult(RESULT_CANCELED, new Intent().putExtra(ScanActivity.EXTRA_CANCELED, true));
                finish();
                break;
            case CONFIRM_CANCEL:
                showConfirmDialog();
                break;
            case CLOSE:
            default:
                finish();
                break;
        }
    }

    /** 识别中返回：「要取消本次识别吗？」 */
    private void showConfirmDialog() {
        if (confirmDialog != null && confirmDialog.isShowing()) {
            return;
        }
        confirmDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scan_analyzing_confirm_title)
                .setMessage(R.string.scan_analyzing_confirm_message)
                .setNegativeButton(R.string.scan_analyzing_confirm_continue, null)
                .setPositiveButton(R.string.scan_analyzing_cancel, (dialog, which) -> viewModel.cancel())
                .show();
    }

    // ---------- 扫描线：在照片高度 8% 与 88% 之间往返，一个来回 2.4 秒，每段先快后慢 ----------

    private void updateScanLine(@Nullable UiState state) {
        if (state == UiState.LOADING) {
            startScanLine();
        } else {
            stopScanLine();
        }
    }

    private void startScanLine() {
        if (scanAnimator != null) {
            return;
        }
        View line = binding.analyzingScanLine;
        View area = binding.analyzingPhotoArea;
        DecelerateInterpolator decelerate = new DecelerateInterpolator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SCAN_CYCLE_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float t = f < 0.5f ? decelerate.getInterpolation(f * 2) : 1f - decelerate.getInterpolation((f - 0.5f) * 2);
            float center = area.getHeight() * (SCAN_TOP + (SCAN_BOTTOM - SCAN_TOP) * t);
            line.setTranslationY(center - line.getHeight() / 2f);
        });
        scanAnimator = animator;
        animator.start();
    }

    private void stopScanLine() {
        if (scanAnimator != null) {
            scanAnimator.cancel();
            scanAnimator = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopScanLine();
        if (confirmDialog != null) {
            confirmDialog.dismiss();
            confirmDialog = null;
        }
        super.onDestroy();
    }
}
