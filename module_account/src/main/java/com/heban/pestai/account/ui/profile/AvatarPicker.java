package com.heban.pestai.account.ui.profile;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.os.BundleCompat;
import androidx.savedstate.SavedStateRegistry;

import com.heban.pestai.account.R;
import com.heban.pestai.account.util.AccountFileProvider;

import java.io.IOException;

/**
 * A06 完善资料 / 编辑资料选择头像：系统相机拍照、系统照片选择器（PickVisualMedia，无需存储权限）。
 * 必须在宿主 onCreate 中创建（ActivityResult 需在 STARTED 之前注册），选好的图片通过 {@link Callback} 交回。
 * <ul>
 *     <li>拍照：先申请 CAMERA 权限（合并清单中声明了 CAMERA 时，未授权直接启动系统相机会抛出 SecurityException），
 *     再用 {@link AccountFileProvider#createCameraUri} + TakePicture 写入应用缓存；取消拍照删除空文件；</li>
 *     <li>系统相机正在写入的文件 Uri 存入宿主的 SavedStateRegistry，拍照期间进程被回收后也能收到这张照片。</li>
 * </ul>
 */
final class AvatarPicker {

    /** 选图结果与提示 */
    interface Callback {
        /** 拍照成功或从相册选好：content:// Uri 字符串 */
        void onAvatarPicked(@NonNull String uri);

        /** 需要提示的失败：没有相机权限、无法打开相机 / 相册 */
        void onPickFailed(@StringRes int message);
    }

    private static final String STATE_KEY = "account_avatar_picker";
    private static final String STATE_CAMERA_URI = "camera_uri";

    private final ComponentActivity activity;
    private final Callback callback;
    private final ActivityResultLauncher<PickVisualMediaRequest> pickImage;
    private final ActivityResultLauncher<String> cameraPermission;
    private final ActivityResultLauncher<Uri> takePicture;
    /** 系统相机正在写入的文件 */
    @Nullable
    private Uri pendingCameraUri;

    /** 在 Activity.onCreate（super.onCreate 之后）中调用 */
    @NonNull
    static AvatarPicker register(@NonNull ComponentActivity activity, @NonNull Callback callback) {
        return new AvatarPicker(activity, callback);
    }

    private AvatarPicker(@NonNull ComponentActivity activity, @NonNull Callback callback) {
        this.activity = activity;
        this.callback = callback;
        pickImage = activity.registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                callback.onAvatarPicked(uri.toString());
            }
        });
        cameraPermission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (Boolean.TRUE.equals(granted)) {
                        launchCamera();
                    } else {
                        callback.onPickFailed(R.string.account_camera_denied);
                    }
                });
        takePicture = activity.registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            Uri uri = pendingCameraUri;
            pendingCameraUri = null;
            if (uri == null) {
                return;
            }
            if (Boolean.TRUE.equals(success)) {
                callback.onAvatarPicked(uri.toString());
            } else {
                AccountFileProvider.deleteQuietly(activity, uri);
            }
        });

        SavedStateRegistry registry = activity.getSavedStateRegistry();
        Bundle restored = registry.consumeRestoredStateForKey(STATE_KEY);
        if (restored != null) {
            pendingCameraUri = BundleCompat.getParcelable(restored, STATE_CAMERA_URI, Uri.class);
        }
        registry.registerSavedStateProvider(STATE_KEY, this::saveState);
    }

    @NonNull
    private Bundle saveState() {
        Bundle state = new Bundle();
        if (pendingCameraUri != null) {
            state.putParcelable(STATE_CAMERA_URI, pendingCameraUri);
        }
        return state;
    }

    /** 拍照：已授权直接打开系统相机，否则先申请 CAMERA 权限 */
    void takePhoto() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    /** 打开系统照片选择器（只选图片，单张） */
    void pickFromAlbum() {
        try {
            pickImage.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } catch (ActivityNotFoundException e) {
            callback.onPickFailed(R.string.account_album_unavailable);
        }
    }

    private void launchCamera() {
        Uri uri = null;
        try {
            uri = AccountFileProvider.createCameraUri(activity);
            pendingCameraUri = uri;
            takePicture.launch(uri);
        } catch (IOException | ActivityNotFoundException | IllegalArgumentException e) {
            pendingCameraUri = null;
            if (uri != null) {
                AccountFileProvider.deleteQuietly(activity, uri);
            }
            callback.onPickFailed(R.string.account_camera_unavailable);
        }
    }
}
