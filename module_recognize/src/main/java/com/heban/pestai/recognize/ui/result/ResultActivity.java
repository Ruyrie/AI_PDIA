package com.heban.pestai.recognize.ui.result;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.SnackbarAnchorProvider;
import com.heban.pestai.base.UiState;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.databinding.ActivityScanResultBinding;
import com.heban.pestai.recognize.model.ResultEvent;
import com.heban.pestai.recognize.util.PhotoLoader;

/**
 * R01 识别结果。带 record_json（S02 / R02 进入）时立即显示；只带 record_id（首页、识别记录、Q02 进入）时先加载详情。
 * 入场动画：五个区块依次淡入并上移 16dp，匹配度进度条 600ms 从 0 增长到目标值；旋转屏幕后不重播。
 */
public class ResultActivity extends BaseActivity implements SnackbarAnchorProvider {

    private static final long BLOCK_MS = 200;
    private static final long BLOCK_GAP_MS = 80;
    private static final long BAR_MS = 600;
    private static final long BAR_DELAY_MS = 160;

    private ActivityScanResultBinding binding;
    private ResultViewModel viewModel;
    /** 从 Q02 引用卡进入：点「追问 AI」直接返回原对话 */
    private boolean fromChat;
    @Nullable
    private ObjectAnimator barAnimator;
    @Nullable
    private String loadedPhoto;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_scan_result);
        viewModel = obtainViewModel(ResultViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        InsetsHelper.applySystemBars(binding.resultRoot);
        observeToast(viewModel);

        Intent intent = getIntent();
        fromChat = intent.getBooleanExtra(RouterPath.EXTRA_FROM_CHAT, false);

        binding.resultToolbar.setNavigationOnClickListener(v -> finish());
        binding.resultOthers.setLayoutManager(new LinearLayoutManager(this));
        binding.resultOthers.setAdapter(new OtherAdapter());
        MaterialDividerItemDecoration divider =
                new MaterialDividerItemDecoration(this, LinearLayoutManager.VERTICAL);
        divider.setDividerColor(MaterialColors.getColor(binding.resultOthers,
                com.google.android.material.R.attr.colorOutlineVariant));
        divider.setDividerThickness(DimenUtils.dp(this, 1));
        divider.setLastItemDecorated(false);
        binding.resultOthers.addItemDecoration(divider);

        viewModel.getRecord().observe(this, this::onRecord);
        viewModel.getEvent().observe(this, this::onEvent);

        viewModel.init(intent.getLongExtra(RouterPath.EXTRA_RECORD_ID, RouterPath.NO_RECORD),
                intent.getStringExtra(RouterPath.EXTRA_RECORD_JSON));
    }

    private void onRecord(@Nullable RecognitionVO record) {
        if (record == null) {
            return;
        }
        String url = record.getImageUrl();
        if (loadedPhoto == null || !loadedPhoto.equals(url)) {
            loadedPhoto = url;
            PhotoLoader.load(binding.resultPhoto, binding.resultPhotoStateIcon, binding.resultPhotoStateText,
                    url, true);
        }
        // 等 DataBinding 刷新内容后再播放动画
        binding.getRoot().post(this::playEnterIfNeeded);
    }

    /** 收到结果且 uiState 为 SUCCESS：未播放过则播放入场动画，否则直接把进度条设为最终值 */
    private void playEnterIfNeeded() {
        if (isFinishing() || isDestroyed() || viewModel.getUiState().getValue() != UiState.SUCCESS) {
            return;
        }
        Integer target = viewModel.getConfidenceProgress().getValue();
        int progress = target == null ? 0 : target;
        if (barAnimator != null) {
            barAnimator.cancel();
        }
        if (viewModel.isAnimated()) {
            binding.resultConfidenceBar.setProgress(progress);
            return;
        }
        viewModel.markAnimated();
        View[] blocks = {binding.resultPhotoCard, binding.resultConclusionCard, binding.resultEvidenceCard,
                binding.resultOthersGroup, binding.resultFooter};
        float offset = DimenUtils.dp(this, 16);
        int index = 0;
        for (View block : blocks) {
            if (block.getVisibility() != View.VISIBLE) {
                continue;
            }
            block.animate().cancel();
            block.setAlpha(0f);
            block.setTranslationY(offset);
            block.animate().alpha(1f).translationY(0f)
                    .setDuration(BLOCK_MS)
                    .setStartDelay(index * BLOCK_GAP_MS)
                    .start();
            index++;
        }
        binding.resultConfidenceBar.setProgress(0);
        barAnimator = ObjectAnimator.ofInt(binding.resultConfidenceBar, "progress", 0, progress);
        barAnimator.setDuration(BAR_MS);
        barAnimator.setStartDelay(BAR_DELAY_MS);
        barAnimator.setInterpolator(new DecelerateInterpolator());
        barAnimator.start();
    }

    private void onEvent(@Nullable ResultEvent event) {
        if (event == null) {
            return;
        }
        switch (event) {
            case ASK_AI:
                askAi();
                break;
            case OPEN_HISTORY:
                openPage(RouterPath.intent(this, RouterPath.HISTORY));
                break;
            case GO_ERROR:
                goError();
                break;
            case CLOSE:
            default:
                finish();
                break;
        }
    }

    /** 追问 AI：从 Q02 进入时直接返回原对话，否则带 record_id 进入 Q02 */
    private void askAi() {
        if (fromChat) {
            finish();
            return;
        }
        openPage(RouterPath.intent(this, RouterPath.AI_CHAT)
                .putExtra(RouterPath.EXTRA_RECORD_ID, viewModel.getRecordId()));
    }

    /** 记录不是识别成功状态：转到 R02 并无动画关闭自己 */
    @SuppressWarnings("deprecation")
    private void goError() {
        RecognitionVO current = viewModel.getCurrent();
        startActivity(RouterPath.intent(this, RouterPath.SCAN_ERROR)
                .putExtra(RouterPath.EXTRA_ERROR_TYPE, viewModel.getErrorType())
                .putExtra(RouterPath.EXTRA_FAIL_REASON, viewModel.getFailReason())
                .putExtra(RouterPath.EXTRA_RECORD_ID, viewModel.getRecordId())
                .putExtra(RouterPath.EXTRA_IMAGE_URL, current == null ? null : current.getImageUrl())
                .putExtra(RouterPath.EXTRA_MODE, current == null ? null : current.getMode()));
        finish();
        overridePendingTransition(0, 0);
    }

    private void openPage(@NonNull Intent intent) {
        if (intent.resolveActivity(getPackageManager()) == null) {
            showSnackbar(getString(R.string.scan_feature_unavailable));
        } else {
            startActivity(intent);
        }
    }

    @Nullable
    @Override
    public View getSnackbarAnchor() {
        return binding.resultBottomBar;
    }

    @Override
    protected void onDestroy() {
        if (barAnimator != null) {
            barAnimator.cancel();
            barAnimator = null;
        }
        View[] blocks = {binding.resultPhotoCard, binding.resultConclusionCard, binding.resultEvidenceCard,
                binding.resultOthersGroup, binding.resultFooter};
        for (View block : blocks) {
            block.animate().cancel();
        }
        super.onDestroy();
    }
}
