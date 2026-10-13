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

import javax.inject.Inject;

/**
 * S01 拍照识别：识别模式、闪光灯、相机权限状态、提示文字、拍照状态与跳转事件。
 * 模式、闪光灯保存在 ViewModel 中，旋转屏幕后不丢失。
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
    private final SingleLiveEvent<ScanEvent> event = new SingleLiveEvent<>();

    /** 本页面是否已申请过相机权限（避免权限对话框关闭后 onResume 反复弹窗） */
    private boolean permissionRequested;
    private boolean cleaned;

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
        showToast(texts.get(R.string.scan_camera_error));
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

    public LiveData<ScanEvent> getEvent() {
        return event;
    }
}
