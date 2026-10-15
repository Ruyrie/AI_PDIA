package com.heban.pestai.recognize.ui.scan;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.LiveData;

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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * S01 拍照识别（深色相机页，主题 Theme.Pest.Camera）。
 * <ul>
 *     <li>CameraX：Preview + ImageCapture 经 bindToLifecycle() 交给 Activity 生命周期管理，页面不手动开关相机；</li>
 *     <li>相机权限、照片选择器（只显示图片）、启动 S02 并接收「已取消识别」结果都用 Activity Result API；</li>
 *     <li>快门 RxJava throttleFirst 500ms + ViewModel 的 capturing 双重防连点；</li>
 *     <li>变焦：取景区底部的档位（0.5 / 1× / 2× / 5×，按设备变焦范围增减，当前档位显示实际倍数）点击后 200ms 动画过渡，
 *     预览上捏合变焦并在中央显示倍数胶囊。目标倍数保存在 ViewModel，旋转重建、从后台回来相机重新打开后恢复。
 *     变焦作用于整个相机会话，ImageCapture 拍出的照片就是当前倍数的画面；</li>
 *     <li>点击预览对焦：在点击处 AF + AE 测光并显示对焦框，3 秒后自动恢复连续对焦；捏合过的触摸不触发对焦。</li>
 * </ul>
 */
public class ScanActivity extends BaseActivity {

    /** S02 取消识别后返回的结果标记 */
    public static final String EXTRA_CANCELED = "canceled";

    private static final long FLASH_MS = 80;
    private static final long HINT_FADE_MS = 150;
    /** 点档位时的变焦动画 */
    private static final long ZOOM_ANIM_MS = 200;
    /** 当前档位变化时档位大小的过渡 */
    private static final long ZOOM_CHIP_RESIZE_MS = 150;
    /** 捏合结束后倍数胶囊先停留、再淡出 */
    private static final long ZOOM_PILL_HOLD_MS = 800;
    private static final long ZOOM_PILL_FADE_MS = 200;
    /** 对焦框：1.25 倍缩到 1 倍（200ms），停留 600ms 后淡出（200ms） */
    private static final float FOCUS_RING_START_SCALE = 1.25f;
    private static final long FOCUS_SCALE_MS = 200;
    private static final long FOCUS_HOLD_MS = 600;
    private static final long FOCUS_FADE_MS = 200;
    /** 点击对焦 / 测光后自动恢复连续对焦的时间（秒） */
    private static final long FOCUS_AUTO_CANCEL_S = 3;

    private ActivityScanBinding binding;
    private ScanViewModel viewModel;
    @Nullable
    private ImageCapture imageCapture;
    @Nullable
    private Camera camera;
    private boolean cameraRequested;
    /** 相机是否处于 OPEN 状态：打开后才接收 ZoomState、响应手势，并在每次打开时恢复目标倍数 */
    private boolean cameraOpen;
    @Nullable
    private LiveData<CameraState> cameraStateData;
    @Nullable
    private LiveData<ZoomState> zoomStateData;
    @Nullable
    private ValueAnimator zoomAnimator;
    /** 本次触摸是否发生过捏合（发生过则抬起时不对焦） */
    private boolean pinched;
    /** 对焦请求序号：新的点击会取消上一次请求，旧请求的失败回调不再淡出新的对焦框 */
    private int focusSeq;

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
        setupCameraGestures();

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
        viewModel.getZoomChips().observe(this, this::renderZoomChips);
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

    @Override
    protected void onStop() {
        super.onStop();
        // 页面不可见时 CameraX 关闭相机并把倍数复位为 1×；回到前台相机重新打开后再恢复目标倍数
        cameraOpen = false;
        cancelZoomAnimation();
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

    /**
     * 获取 ProcessCameraProvider → 创建 Preview、ImageCapture → unbindAll() 后 bindToLifecycle()，
     * 然后观察相机状态与 ZoomState（变焦对 Preview、ImageCapture 同时生效，拍照不需要另外处理）。
     */
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
                Camera bound = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture);
                imageCapture = capture;
                camera = bound;
                viewModel.setHasFlash(bound.getCameraInfo().hasFlashUnit());
                observeCamera(bound.getCameraInfo());
            } catch (Exception e) {
                // 相机被其他应用占用、设备没有后置摄像头等
                cameraRequested = false;
                imageCapture = null;
                camera = null;
                stopObservingCamera();
                viewModel.onCameraError();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 观察相机状态与 ZoomState：每次打开（首次绑定、旋转重建、从后台回来）后恢复 ViewModel 中的目标倍数 */
    private void observeCamera(@NonNull CameraInfo info) {
        stopObservingCamera();
        zoomStateData = info.getZoomState();
        cameraStateData = info.getCameraState();
        zoomStateData.observe(this, state -> {
            // 相机关闭期间 CameraX 会把倍数复位为 1×，忽略，避免档位闪动
            if (!cameraOpen || state == null) {
                return;
            }
            if (viewModel.isZoomRangeChanged(state.getMinZoomRatio(), state.getMaxZoomRatio())) {
                // 相机刚打开时 CameraX 可能先给出 1–1 的默认范围，真实范围随后到达：重新生成档位并恢复目标倍数
                restoreZoom(state);
            } else {
                viewModel.onZoomRatio(state.getZoomRatio());
            }
        });
        cameraStateData.observe(this, state -> {
            boolean open = state != null && state.getType() == CameraState.Type.OPEN;
            boolean opened = open && !cameraOpen;
            cameraOpen = open;
            if (opened) {
                restoreZoom(info.getZoomState().getValue());
            }
        });
    }

    private void stopObservingCamera() {
        if (zoomStateData != null) {
            zoomStateData.removeObservers(this);
            zoomStateData = null;
        }
        if (cameraStateData != null) {
            cameraStateData.removeObservers(this);
            cameraStateData = null;
        }
        cameraOpen = false;
    }

    /** 相机打开后把变焦范围交给 ViewModel，并恢复其中保存的目标倍数（新打开页面为 1×） */
    private void restoreZoom(@Nullable ZoomState state) {
        if (state != null) {
            setCameraZoom(viewModel.onZoomRange(state.getMinZoomRatio(), state.getMaxZoomRatio()));
        }
    }

    private void setCameraZoom(float ratio) {
        Camera current = camera;
        if (current != null) {
            // 返回的 Future 不需要处理：连续调用时前一次被取消属正常；相机未打开时 CameraX 直接返回失败
            current.getCameraControl().setZoomRatio(ratio);
        }
    }

    /** 相机已打开且已知变焦范围，才响应捏合与档位 */
    private boolean zoomReady() {
        return camera != null && cameraOpen && viewModel.isZoomKnown();
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

    // ---------- 变焦档位 ----------

    /** 按 ViewModel 的档位数据渲染：档位数变化时重建，否则只更新文字、选中态、无障碍描述与大小 */
    private void renderZoomChips(@Nullable List<ZoomChip> chips) {
        LinearLayout bar = binding.scanZoomBar;
        List<ZoomChip> list = chips != null ? chips : Collections.emptyList();
        if (bar.getChildCount() != list.size()) {
            bar.removeAllViews();
            for (int i = 0; i < list.size(); i++) {
                View chip = getLayoutInflater().inflate(R.layout.item_scan_zoom_chip, bar, false);
                int index = i;
                chip.setOnClickListener(v -> onZoomChipClick(index));
                bar.addView(chip);
            }
        }
        boolean activeChanged = false;
        for (int i = 0; i < list.size(); i++) {
            if (bar.getChildAt(i).isSelected() != list.get(i).active) {
                activeChanged = true;
                break;
            }
        }
        if (activeChanged && bar.isLaidOut()) {
            // 当前档位换了：新旧档位 36dp 与 40dp 之间的尺寸变化做一次过渡
            TransitionManager.beginDelayedTransition(bar, new ChangeBounds().setDuration(ZOOM_CHIP_RESIZE_MS));
        }
        int normalSize = getResources().getDimensionPixelSize(R.dimen.scan_zoom_chip_size);
        int activeSize = getResources().getDimensionPixelSize(R.dimen.scan_zoom_chip_size_active);
        for (int i = 0; i < list.size(); i++) {
            ZoomChip chip = list.get(i);
            TextView view = (TextView) bar.getChildAt(i);
            view.setText(chip.label);
            view.setSelected(chip.active);
            view.setContentDescription(chip.description);
            int size = chip.active ? activeSize : normalSize;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params.width != size || params.height != size) {
                params.width = size;
                params.height = size;
                view.setLayoutParams(params);
            }
        }
    }

    private void onZoomChipClick(int index) {
        List<ZoomChip> chips = viewModel.getZoomChips().getValue();
        if (chips != null && index < chips.size()) {
            animateZoomTo(chips.get(index).preset);
        }
    }

    /** 点档位：200ms 内把倍数按比例过渡到档位倍数（ValueAnimator 逐帧调用 setZoomRatio） */
    private void animateZoomTo(float target) {
        if (!zoomReady()) {
            return;
        }
        cancelZoomAnimation();
        float from = viewModel.getZoomTarget();
        if (Math.abs(from - target) < 0.001f) {
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ZOOM_ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> setCameraZoom(viewModel.requestZoom(
                ZoomPresets.interpolate(from, target, (float) a.getAnimatedValue()))));
        zoomAnimator = animator;
        animator.start();
    }

    private void cancelZoomAnimation() {
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
    }

    // ---------- 手势：捏合变焦、点击对焦 ----------

    /**
     * 预览上的手势：ScaleGestureDetector 处理捏合变焦，GestureDetector 的单击用于对焦。
     * 第二根手指按下时 GestureDetector 会取消单击，捏合过的触摸序列也不再对焦，两者互不冲突。
     * 档位、顶部按钮等控件叠在预览之上，点击它们不会传到这里。
     */
    @SuppressLint("ClickableViewAccessibility") // 点击对焦依赖触摸坐标，没有对应的无障碍操作；相机本身保持连续自动对焦
    private void setupCameraGestures() {
        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                        if (!zoomReady()) {
                            return false;
                        }
                        pinched = true;
                        cancelZoomAnimation();
                        showZoomPill(viewModel.getZoomTarget());
                        return true;
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        // 返回 true：下次的缩放系数相对本次计算，目标倍数逐次累乘
                        float ratio = viewModel.scaleZoom(detector.getScaleFactor());
                        setCameraZoom(ratio);
                        binding.scanZoomPill.setText(ZoomPresets.format(ratio));
                        return true;
                    }

                    @Override
                    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                        fadeOut(binding.scanZoomPill, ZOOM_PILL_HOLD_MS, ZOOM_PILL_FADE_MS);
                    }
                });
        // 双击后拖动缩放（quick scale）容易和单击对焦混淆，关闭
        scaleDetector.setQuickScaleEnabled(false);
        GestureDetector tapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (!pinched && !isOnZoomBar(e.getX(), e.getY())) {
                    focusAt(e.getX(), e.getY());
                }
                return true;
            }
        });
        // 按住稍久再抬起也算点击对焦
        tapDetector.setIsLongpressEnabled(false);
        binding.scanPreview.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pinched = false;
            }
            scaleDetector.onTouchEvent(event);
            tapDetector.onTouchEvent(event);
            return true;
        });
    }

    /** 点在档位之间的空隙（档位条范围内）不算点击预览；预览与档位条同在取景区内，坐标系相同 */
    private boolean isOnZoomBar(float x, float y) {
        View bar = binding.scanZoomBar;
        return bar.getVisibility() == View.VISIBLE
                && x >= bar.getLeft() && x <= bar.getRight() && y >= bar.getTop() && y <= bar.getBottom();
    }

    private void showZoomPill(float ratio) {
        TextView pill = binding.scanZoomPill;
        pill.animate().cancel();
        pill.setText(ZoomPresets.format(ratio));
        pill.setAlpha(1f);
        pill.setVisibility(View.VISIBLE);
    }

    /** 点击对焦：PreviewView 的 MeteringPointFactory 把点击坐标换算到传感器，AF + AE，3 秒后自动恢复连续对焦 */
    private void focusAt(float x, float y) {
        Camera current = camera;
        if (current == null || !cameraOpen) {
            return;
        }
        MeteringPoint point = binding.scanPreview.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(FOCUS_AUTO_CANCEL_S, TimeUnit.SECONDS)
                .build();
        int seq = ++focusSeq;
        showFocusRing(x, y);
        ListenableFuture<FocusMeteringResult> future = current.getCameraControl().startFocusAndMetering(action);
        future.addListener(() -> {
            try {
                future.get();
            } catch (Exception e) {
                // 设备不支持、相机已关闭等：对焦框直接淡出；被新的点击取消时由新的对焦框接管
                if (seq == focusSeq) {
                    binding.scanFocusRing.animate().cancel();
                    fadeOut(binding.scanFocusRing, 0, FOCUS_FADE_MS);
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 对焦框移到点击处（不超出取景区），1.25 → 1 倍缩放后停留，再淡出 */
    private void showFocusRing(float x, float y) {
        View ring = binding.scanFocusRing;
        View area = binding.scanViewfinder;
        ring.animate().cancel();
        ring.setTranslationX(Math.max(0f, Math.min(x - ring.getWidth() / 2f, area.getWidth() - ring.getWidth())));
        ring.setTranslationY(Math.max(0f, Math.min(y - ring.getHeight() / 2f, area.getHeight() - ring.getHeight())));
        ring.setAlpha(1f);
        ring.setScaleX(FOCUS_RING_START_SCALE);
        ring.setScaleY(FOCUS_RING_START_SCALE);
        ring.setVisibility(View.VISIBLE);
        ring.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(FOCUS_SCALE_MS)
                .withEndAction(() -> fadeOut(ring, FOCUS_HOLD_MS, FOCUS_FADE_MS))
                .start();
    }

    /** 停留 delay 后用 duration 淡出，结束时设为 INVISIBLE（ViewPropertyAnimator 会沿用上次的 startDelay，这里每次显式设置） */
    private static void fadeOut(@NonNull View view, long delay, long duration) {
        view.animate().alpha(0f).setStartDelay(delay).setDuration(duration)
                .withEndAction(() -> view.setVisibility(View.INVISIBLE))
                .start();
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
        binding.scanZoomPill.animate().cancel();
        binding.scanFocusRing.animate().cancel();
        cancelZoomAnimation();
        super.onDestroy();
    }
}
