package com.heban.pestai.recognize.ui.scan;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.Surface;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.google.common.util.concurrent.ListenableFuture;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.data.RecognizeRepository;
import com.heban.pestai.recognize.databinding.ActivityScanBinding;
import com.heban.pestai.recognize.model.PermissionState;
import com.heban.pestai.recognize.model.ScanEvent;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * S01 拍照识别（深色相机页，主题 Theme.Pest.Camera）。
 * <ul>
 *     <li>CameraX：Preview + ImageCapture 经 bindToLifecycle() 交给 Activity 生命周期管理，页面不手动开关相机；</li>
 *     <li>相机权限、照片选择器（只显示图片）、启动 S02 并接收「已取消识别」结果都用 Activity Result API；</li>
 *     <li>快门 RxJava throttleFirst 500ms + ViewModel 的 capturing 双重防连点。</li>
 * </ul>
 */
public class ScanActivity extends BaseActivity {

    /** S02 取消识别后返回的结果标记 */
    public static final String EXTRA_CANCELED = "canceled";

    private static final long FLASH_MS = 80;
    private static final long HINT_FADE_MS = 150;

    private ActivityScanBinding binding;
    private ScanViewModel viewModel;
    @Nullable
    private ImageCapture imageCapture;
    private boolean cameraRequested;

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> pickLauncher;
    private ActivityResultLauncher<Intent> analyzingLauncher;

    @Override
    protected boolean useDarkSystemBars() {
        return true;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_scan);
        viewModel = obtainViewModel(ScanViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        InsetsHelper.applySystemBars(binding.scanRoot);
        observeToast(viewModel);

        viewModel.initMode(getIntent().getStringExtra(RouterPath.EXTRA_MODE));

        // Activity Result 启动器必须在 STARTED 之前注册
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> viewModel.onPermissionResult(granted,
                        shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)));
        pickLauncher = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onPicked);
        analyzingLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (data != null && data.getBooleanExtra(EXTRA_CANCELED, false)) {
                viewModel.onAnalyzeCanceled();
            }
        });

        binding.scanClose.setOnClickListener(v -> finish());
        binding.scanAlbum.setOnClickListener(v -> pickLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
        RxClick.throttle(binding.scanShutter, v -> takePhoto());

        binding.scanPreview.getPreviewStreamState().observe(this, streamState -> updatePlaceholder());
        viewModel.getPermissionState().observe(this, state -> {
            updatePlaceholder();
            if (state == PermissionState.GRANTED) {
                bindCamera();
            }
        });
        viewModel.getFlashOn().observe(this, on -> {
            if (imageCapture != null) {
                imageCapture.setFlashMode(Boolean.TRUE.equals(on)
                        ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
            }
        });
        viewModel.getHintText().observe(this, this::showHint);
        viewModel.getEvent().observe(this, this::onEvent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到前台都检查（从系统设置返回时已授权则立即出现预览）
        viewModel.checkPermission(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED);
        // 上次相机启动失败（如被其他应用占用）时，回到前台再试一次
        if (viewModel.getPermissionState().getValue() == PermissionState.GRANTED && imageCapture == null) {
            bindCamera();
        }
    }

    private void onEvent(@Nullable ScanEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type) {
            case REQUEST_PERMISSION:
                permissionLauncher.launch(Manifest.permission.CAMERA);
                break;
            case OPEN_SETTINGS:
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
                break;
            case GO_ANALYZING:
                if (event.imageUri != null && event.mode != null) {
                    openAnalyzing(event.imageUri, event.mode.value());
                }
                break;
            case GO_HISTORY:
                openPage(RouterPath.intent(this, RouterPath.HISTORY));
                break;
            default:
                break;
        }
    }

    private void openAnalyzing(@NonNull String imageUri, @NonNull String mode) {
        Intent intent = RouterPath.intent(this, RouterPath.ANALYZING)
                .putExtra(RouterPath.EXTRA_IMAGE_URI, imageUri)
                .putExtra(RouterPath.EXTRA_MODE, mode);
        Uri uri = Uri.parse(imageUri);
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            // 照片选择器返回的 Uri：把读取授权一并交给 S02 与 RecognizeService
            intent.setClipData(ClipData.newRawUri("", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        analyzingLauncher.launch(intent);
    }

    private void openPage(@NonNull Intent intent) {
        if (intent.resolveActivity(getPackageManager()) == null) {
            showSnackbar(getString(R.string.scan_feature_unavailable));
        } else {
            startActivity(intent);
        }
    }

    // ---------- 相机 ----------

    /** 获取 ProcessCameraProvider → 创建 Preview、ImageCapture → unbindAll() 后 bindToLifecycle() */
    private void bindCamera() {
        if (cameraRequested) {
            return;
        }
        cameraRequested = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.scanPreview.getSurfaceProvider());
                ImageCapture capture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(Boolean.TRUE.equals(viewModel.getFlashOn().getValue())
                                ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF)
                        .setTargetRotation(displayRotation())
                        .build();
                provider.unbindAll();
                Camera camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture);
                imageCapture = capture;
                viewModel.setHasFlash(camera.getCameraInfo().hasFlashUnit());
            } catch (Exception e) {
                // 相机被其他应用占用、设备没有后置摄像头等
                cameraRequested = false;
                imageCapture = null;
                viewModel.onCameraError();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        ImageCapture capture = imageCapture;
        if (capture == null || !viewModel.startCapture()) {
            return;
        }
        File dir = RecognizeRepository.scanDir(this);
        if (!dir.exists() && !dir.mkdirs()) {
            viewModel.onCaptureFailed();
            return;
        }
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        File file = new File(dir, "IMG_" + time + ".jpg");
        // 方向写入 EXIF，压缩时据此摆正
        capture.setTargetRotation(displayRotation());
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file).build();
        playCaptureFlash();
        capture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                Uri saved = results.getSavedUri();
                viewModel.onPhotoCaptured((saved != null ? saved : Uri.fromFile(file)).toString());
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                viewModel.onCaptureFailed();
            }
        });
    }

    private int displayRotation() {
        Display display = binding.scanPreview.getDisplay();
        if (display == null) {
            display = getDisplay();
        }
        return display != null ? display.getRotation() : Surface.ROTATION_0;
    }

    /** 拍下瞬间取景区闪白一次（80ms） */
    private void playCaptureFlash() {
        View overlay = binding.scanFlashOverlay;
        overlay.animate().cancel();
        overlay.setAlpha(0.8f);
        overlay.animate().alpha(0f).setDuration(FLASH_MS).start();
    }

    /** 预览开始输出画面前显示 eco 占位；权限被拒时由说明层取代 */
    private void updatePlaceholder() {
        PermissionState state = viewModel.getPermissionState().getValue();
        boolean denied = state != null && state.isDenied();
        boolean streaming = state == PermissionState.GRANTED
                && binding.scanPreview.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING;
        binding.scanPlaceholder.setVisibility(denied || streaming ? View.GONE : View.VISIBLE);
    }

    // ---------- 提示文字：先淡出再淡入（各 150ms） ----------

    private void showHint(@Nullable String text) {
        if (TextUtils.isEmpty(binding.scanHint.getText()) || !binding.scanHint.isLaidOut()) {
            binding.scanHint.setText(text);
            return;
        }
        if (TextUtils.equals(binding.scanHint.getText(), text)) {
            return;
        }
        binding.scanHint.animate().cancel();
        binding.scanHint.animate().alpha(0f).setDuration(HINT_FADE_MS).withEndAction(() -> {
            binding.scanHint.setText(text);
            binding.scanHint.animate().alpha(1f).setDuration(HINT_FADE_MS).start();
        }).start();
    }

    // ---------- 相册 ----------

    private void onPicked(@Nullable Uri uri) {
        if (uri == null) {
            // 用户取消选择，停留在 S01
            return;
        }
        String type = getContentResolver().getType(uri);
        if (type != null && !type.startsWith("image/")) {
            viewModel.onPickedNotImage();
            return;
        }
        viewModel.onPhotoPicked(uri.toString());
    }

    @Override
    protected void onDestroy() {
        binding.scanHint.animate().cancel();
        binding.scanFlashOverlay.animate().cancel();
        super.onDestroy();
    }
}
