package com.heban.pestai.recognize.ui.error;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.SnackbarAnchorProvider;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.databinding.ActivityScanErrorBinding;
import com.heban.pestai.recognize.model.ErrorEvent;
import com.heban.pestai.recognize.util.PhotoLoader;

/**
 * R02 识别异常：照片不清晰（IMAGE_BAD）/ 识别失败（FAILED）。
 * 入口：S02 识别失败、R03 点击失败记录、R01 发现记录不是成功状态。
 */
public class ScanErrorActivity extends BaseActivity implements SnackbarAnchorProvider {

    private static final long ENTER_MS = 250;

    private ActivityScanErrorBinding binding;
    private ScanErrorViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_scan_error);
        viewModel = obtainViewModel(ScanErrorViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        InsetsHelper.applySystemBars(binding.errorRoot);
        observeToast(viewModel);

        Intent intent = getIntent();
        viewModel.init(intent.getStringExtra(RouterPath.EXTRA_ERROR_TYPE),
                intent.getStringExtra(RouterPath.EXTRA_FAIL_REASON),
                intent.getLongExtra(RouterPath.EXTRA_RECORD_ID, RouterPath.NO_RECORD),
                intent.getStringExtra(RouterPath.EXTRA_IMAGE_URL),
                intent.getStringExtra(RouterPath.EXTRA_IMAGE_URI),
                intent.getStringExtra(RouterPath.EXTRA_MODE));

        binding.errorToolbar.setNavigationOnClickListener(v -> finish());
        viewModel.getPhoto().observe(this, photo -> PhotoLoader.load(binding.errorPhoto,
                binding.errorPhotoStateIcon, binding.errorPhotoStateText, photo, !isLocal(photo)));
        viewModel.getEvent().observe(this, this::onEvent);

        if (savedInstanceState == null) {
            playEnter();
        }
    }

    private static boolean isLocal(@Nullable String photo) {
        return photo != null && (photo.startsWith(ContentResolver.SCHEME_CONTENT)
                || photo.startsWith(ContentResolver.SCHEME_FILE));
    }

    /** 状态图标从 0.8 倍放大并淡入（250ms），文字与列表淡入 */
    private void playEnter() {
        View icon = binding.errorIconBg;
        icon.setScaleX(0.8f);
        icon.setScaleY(0.8f);
        icon.setAlpha(0f);
        icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(ENTER_MS).start();
        View[] fades = {binding.errorTitle, binding.errorMessage, binding.errorTipsGroup};
        for (View view : fades) {
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(ENTER_MS).setStartDelay(80).start();
        }
    }

    private void onEvent(@Nullable ErrorEvent event) {
        if (event == null) {
            return;
        }
        switch (event) {
            case RETAKE:
                // 回到相机：返回栈中已有 S01 时清掉其上的页面，没有时新建；保持原识别模式
                startActivity(RouterPath.intent(this, RouterPath.SCAN)
                        .putExtra(RouterPath.EXTRA_MODE, viewModel.getModeValue())
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
                break;
            case REUPLOAD:
                reupload();
                break;
            case GO_RESULT:
                startActivity(RouterPath.intent(this, RouterPath.SCAN_RESULT)
                        .putExtra(RouterPath.EXTRA_RECORD_ID, viewModel.getResultId())
                        .putExtra(RouterPath.EXTRA_RECORD_JSON, viewModel.getResultJson()));
                finish();
                break;
            case OPEN_HISTORY:
                openPage(RouterPath.intent(this, RouterPath.HISTORY));
                break;
            default:
                break;
        }
    }

    /** 没有识别记录（照片未上传成功）：带本机照片进入 S02 重新上传识别 */
    private void reupload() {
        String imageUri = viewModel.getImageUri();
        if (imageUri == null) {
            return;
        }
        Intent intent = RouterPath.intent(this, RouterPath.ANALYZING)
                .putExtra(RouterPath.EXTRA_IMAGE_URI, imageUri)
                .putExtra(RouterPath.EXTRA_MODE, viewModel.getModeValue());
        Uri uri = Uri.parse(imageUri);
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            intent.setClipData(ClipData.newRawUri("", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        startActivity(intent);
        finish();
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
        return binding.errorBottomBar;
    }

    @Override
    protected void onDestroy() {
        binding.errorIconBg.animate().cancel();
        binding.errorTitle.animate().cancel();
        binding.errorMessage.animate().cancel();
        binding.errorTipsGroup.animate().cancel();
        super.onDestroy();
    }
}
