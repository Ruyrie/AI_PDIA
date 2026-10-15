package com.heban.pestai.recognize.ui.scan;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.data.RecognizeRepository;
import com.heban.pestai.recognize.model.PermissionState;
import com.heban.pestai.recognize.model.RecognizeMode;
import com.heban.pestai.recognize.model.ScanEvent;
import com.heban.pestai.recognize.util.TextSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/**
 * S01 拍照识别：识别模式、闪光灯、变焦、相机权限状态、提示文字、拍照状态与跳转事件。
 * 模式、闪光灯、目标变焦倍数保存在 ViewModel 中，旋转屏幕后不丢失；每次新打开页面变焦从 1× 开始。
 */
public class ScanViewModel extends BaseViewModel {

    private static final String TAG = "ScanViewModel";

    private final RecognizeRepository repository;
    private final TextSource texts;

    private final MutableLiveData<RecognizeMode> mode = new MutableLiveData<>();
    private final MutableLiveData<String> hintText = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> flashOn = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> hasFlash = new MutableLiveData<>(false);
    private final MutableLiveData<PermissionState> permissionState = new MutableLiveData<>(PermissionState.UNKNOWN);
    private final MutableLiveData<String> permissionDesc = new MutableLiveData<>("");
    private final MutableLiveData<String> permissionButtonText = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> capturing = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> shutterEnabled = new MutableLiveData<>(false);
    private final MutableLiveData<List<ZoomChip>> zoomChips = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Boolean> zoomBarVisible = new MutableLiveData<>(false);
    private final SingleLiveEvent<ScanEvent> event = new SingleLiveEvent<>();

    /** 本页面是否已申请过相机权限（避免权限对话框关闭后 onResume 反复弹窗） */
    private boolean permissionRequested;
    private boolean cleaned;

    /** 是否已得知相机的变焦范围（相机打开后由 Activity 写入；相机出错时清除） */
    private boolean zoomKnown;
    private float zoomMin = 1f;
    private float zoomMax = 1f;
    @NonNull
    private List<Float> zoomPresets = Collections.emptyList();
    /** 用户要求的倍数（捏合、点档位）：重建页面、重新打开相机后据此恢复 */
    private float zoomTarget = 1f;
    /** 相机实际倍数（CameraX ZoomState），当前档位据此高亮并显示 */
    private float zoomRatio = 1f;

    @Inject
    public ScanViewModel(RecognizeRepository repository, TextSource texts) {
        this.repository = repository;
        this.texts = texts;
    }

    // ---------- 模式 ----------

    /** 首次创建时按 Intent 的 EXTRA_MODE 预选模式（缺省 PEST）；旋转重建时 mode 已有值，不覆盖 */
    public void initMode(@Nullable String extraMode) {
        if (mode.getValue() != null) {
            return;
        }
        applyMode(RecognizeMode.of(extraMode));
        if (!cleaned) {
            cleaned = true;
            add(repository.cleanOldFiles().subscribe(() -> {
            }, e -> Log.w(TAG, "清理临时照片失败", e)));
        }
    }

    public void selectMode(@NonNull RecognizeMode newMode) {
        if (newMode != mode.getValue()) {
            applyMode(newMode);
        }
    }

    private void applyMode(@NonNull RecognizeMode newMode) {
        mode.setValue(newMode);
        hintText.setValue(texts.get(newMode == RecognizeMode.CROP ? R.string.scan_hint_crop : R.string.scan_hint_pest));
    }

    // ---------- 闪光灯 ----------

    public void toggleFlash() {
        flashOn.setValue(!Boolean.TRUE.equals(flashOn.getValue()));
    }

    /** 相机绑定成功后写入设备是否有闪光灯 */
    public void setHasFlash(boolean value) {
        hasFlash.setValue(value);
    }

    public void onCameraError() {
        zoomKnown = false;
        refreshZoomBar();
        showToast(texts.get(R.string.scan_camera_error));
    }

    // ---------- 变焦 ----------

    /**
     * 相机打开后写入变焦范围：生成档位并显示档位条，返回需要恢复的目标倍数（已按范围夹紧）。
     * 返回值先作为显示倍数，避免恢复前档位闪一下 1×。
     */
    public float onZoomRange(float min, float max) {
        if (isZoomRangeChanged(min, max)) {
            zoomKnown = true;
            zoomMin = min;
            zoomMax = max;
            zoomPresets = ZoomPresets.of(min, max);
            refreshZoomBar();
        }
        float restore = ZoomPresets.clamp(zoomTarget, zoomMin, zoomMax);
        // 1–1 可能是相机刚打开时的默认范围（真实范围随后到达）：不改写目标倍数，旋转前选的 2× 等到真实范围时再恢复
        if (zoomMax > zoomMin) {
            zoomTarget = restore;
        }
        zoomRatio = restore;
        refreshZoomChips();
        return restore;
    }

    /** 变焦范围与已知范围不同（或还未知）：需要重新生成档位并恢复目标倍数 */
    public boolean isZoomRangeChanged(float min, float max) {
        return !zoomKnown || min != zoomMin || max != zoomMax;
    }

    /** CameraX ZoomState 变化（相机实际倍数） */
    public void onZoomRatio(float ratio) {
        if (ratio != zoomRatio) {
            zoomRatio = ratio;
            refreshZoomChips();
        }
    }

    /** 用户要求缩放到 ratio（点档位动画的每一帧）：按范围夹紧后记为目标倍数并返回；范围未知时不变 */
    public float requestZoom(float ratio) {
        if (zoomKnown) {
            zoomTarget = ZoomPresets.clamp(ratio, zoomMin, zoomMax);
        }
        return zoomTarget;
    }

    /** 捏合缩放：目标倍数 × 本次缩放系数，夹紧后返回 */
    public float scaleZoom(float scaleFactor) {
        return requestZoom(zoomTarget * scaleFactor);
    }

    /** 目标倍数（点档位动画的起点） */
    public float getZoomTarget() {
        return zoomTarget;
    }

    public boolean isZoomKnown() {
        return zoomKnown;
    }

    /** 当前档位显示实际倍数，其余档位显示档位倍数；内容不变时不发通知（捏合时 ZoomState 很密集） */
    private void refreshZoomChips() {
        int active = ZoomPresets.activeIndex(zoomPresets, zoomRatio);
        List<ZoomChip> chips = new ArrayList<>(zoomPresets.size());
        for (int i = 0; i < zoomPresets.size(); i++) {
            float preset = zoomPresets.get(i);
            boolean isActive = i == active;
            String presetNumber = ZoomPresets.number(preset);
            String ratioNumber = ZoomPresets.number(zoomRatio);
            String description = isActive && !presetNumber.equals(ratioNumber)
                    ? texts.get(R.string.scan_zoom_chip_desc_current, presetNumber, ratioNumber)
                    : texts.get(R.string.scan_zoom_chip_desc, presetNumber);
            chips.add(new ZoomChip(preset, ZoomPresets.format(isActive ? zoomRatio : preset), isActive, description));
        }
        if (!chips.equals(zoomChips.getValue())) {
            zoomChips.setValue(Collections.unmodifiableList(chips));
        }
    }

    /** 档位条：已授权、已得知变焦范围且至少有两个档位（不能变焦的设备不显示） */
    private void refreshZoomBar() {
        boolean visible = permissionState.getValue() == PermissionState.GRANTED
                && zoomKnown && zoomPresets.size() >= 2;
        if (!Boolean.valueOf(visible).equals(zoomBarVisible.getValue())) {
            zoomBarVisible.setValue(visible);
        }
    }

    // ---------- 相机权限 ----------

    /** onResume 时调用：已授权 → GRANTED；未授权且本页面尚未申请过 → 发出 REQUEST_PERMISSION */
    public void checkPermission(boolean granted) {
        if (granted) {
            setPermission(PermissionState.GRANTED);
        } else if (!permissionRequested) {
            permissionRequested = true;
            event.setValue(ScanEvent.of(ScanEvent.Type.REQUEST_PERMISSION));
        } else if (permissionState.getValue() == PermissionState.GRANTED
                || permissionState.getValue() == PermissionState.UNKNOWN) {
            // 曾授权后又被收回，或对话框尚未返回结果
            setPermission(PermissionState.DENIED);
        }
    }

    /**
     * 权限对话框结果：授权 → GRANTED；拒绝且 showRationale 为 true → DENIED（还能再次申请）；
     * 拒绝且为 false → PERMANENTLY_DENIED（勾选了不再询问，只能去系统设置开启）。
     */
    public void onPermissionResult(boolean granted, boolean showRationale) {
        permissionRequested = true;
        if (granted) {
            setPermission(PermissionState.GRANTED);
        } else {
            setPermission(showRationale ? PermissionState.DENIED : PermissionState.PERMANENTLY_DENIED);
        }
    }

    public void onPermissionButtonClick() {
        if (permissionState.getValue() == PermissionState.PERMANENTLY_DENIED) {
            event.setValue(ScanEvent.of(ScanEvent.Type.OPEN_SETTINGS));
        } else {
            event.setValue(ScanEvent.of(ScanEvent.Type.REQUEST_PERMISSION));
        }
    }

    private void setPermission(@NonNull PermissionState state) {
        if (state == permissionState.getValue()) {
            return;
        }
        permissionState.setValue(state);
        if (state == PermissionState.PERMANENTLY_DENIED) {
            permissionDesc.setValue(texts.get(R.string.scan_permission_desc_permanent));
            permissionButtonText.setValue(texts.get(R.string.scan_permission_open_settings));
        } else {
            permissionDesc.setValue(texts.get(R.string.scan_permission_desc));
            permissionButtonText.setValue(texts.get(R.string.scan_permission_grant));
        }
        refreshShutter();
        refreshZoomBar();
    }

    // ---------- 拍照与选图 ----------

    /** 已授权且不在拍照中时进入拍照状态并返回 true，Activity 收到 true 才调用 takePicture() */
    public boolean startCapture() {
        if (permissionState.getValue() != PermissionState.GRANTED || Boolean.TRUE.equals(capturing.getValue())) {
            return false;
        }
        capturing.setValue(true);
        refreshShutter();
        return true;
    }

    /** 拍照成功：按设置在后台保存原图到相册（失败忽略、不阻塞跳转），然后进入 S02 */
    public void onPhotoCaptured(@NonNull String imageUri) {
        capturing.setValue(false);
        refreshShutter();
        if (repository.isSaveToAlbum()) {
            add(repository.saveToAlbum(imageUri).subscribe(() -> {
            }, e -> Log.w(TAG, "保存到相册失败", e)));
        }
        event.setValue(ScanEvent.analyzing(imageUri, currentMode()));
    }

    public void onCaptureFailed() {
        capturing.setValue(false);
        refreshShutter();
        showToast(texts.get(R.string.scan_capture_failed));
    }

    /** 照片选择器返回：用户取消（null）时停留在 S01；相册照片不再重复保存 */
    public void onPhotoPicked(@Nullable String imageUri) {
        if (imageUri == null || imageUri.isEmpty()) {
            return;
        }
        event.setValue(ScanEvent.analyzing(imageUri, currentMode()));
    }

    /** 选中的文件不是图片 */
    public void onPickedNotImage() {
        showToast(texts.get(R.string.scan_pick_not_image));
    }

    /** S02 取消识别后返回 */
    public void onAnalyzeCanceled() {
        showToast(texts.get(R.string.scan_canceled));
    }

    public void openHistory() {
        event.setValue(ScanEvent.of(ScanEvent.Type.GO_HISTORY));
    }

    private void refreshShutter() {
        boolean enabled = permissionState.getValue() == PermissionState.GRANTED
                && !Boolean.TRUE.equals(capturing.getValue());
        if (!Boolean.valueOf(enabled).equals(shutterEnabled.getValue())) {
            shutterEnabled.setValue(enabled);
        }
    }

    @NonNull
    private RecognizeMode currentMode() {
        RecognizeMode value = mode.getValue();
        return value == null ? RecognizeMode.PEST : value;
    }

    // ---------- getter ----------

    public LiveData<RecognizeMode> getMode() {
        return mode;
    }

    public LiveData<String> getHintText() {
        return hintText;
    }

    public LiveData<Boolean> getFlashOn() {
        return flashOn;
    }

    public LiveData<Boolean> getHasFlash() {
        return hasFlash;
    }

    public LiveData<PermissionState> getPermissionState() {
        return permissionState;
    }

    public LiveData<String> getPermissionDesc() {
        return permissionDesc;
    }

    public LiveData<String> getPermissionButtonText() {
        return permissionButtonText;
    }

    public LiveData<Boolean> getCapturing() {
        return capturing;
    }

    /** 快门可用：已授权且不在拍照中 */
    public LiveData<Boolean> getShutterEnabled() {
        return shutterEnabled;
    }

    /** 变焦档位（升序），档位条显示时才有意义 */
    public LiveData<List<ZoomChip>> getZoomChips() {
        return zoomChips;
    }

    public LiveData<Boolean> getZoomBarVisible() {
        return zoomBarVisible;
    }

    public LiveData<ScanEvent> getEvent() {
        return event;
    }
}
