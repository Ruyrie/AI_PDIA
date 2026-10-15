package com.heban.pestai.ai.util;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.os.BundleCompat;
import androidx.fragment.app.Fragment;
import androidx.savedstate.SavedStateRegistry;
import androidx.savedstate.SavedStateRegistryOwner;

import com.heban.pestai.ai.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Q01 / Q02 输入栏添加图片：系统相机拍照、系统照片选择器多选（Fragment 或 ComponentActivity 共用）。
 * 必须在宿主 onCreate 中创建（ActivityResult 需在 STARTED 之前注册），选好的图片通过 {@link Callback} 交回。
 * <ul>
 *     <li>拍照：先申请 CAMERA 权限（合并清单中声明了 CAMERA 时，未授权直接启动系统相机会抛出 SecurityException），
 *     再用 {@link AiFileProvider#createCameraUri(Context)} + TakePicture；取消拍照删除空文件；
 *     系统相机正在写入的文件 Uri 存入宿主的 SavedStateRegistry，进程被回收后也能收到这张照片；每次拍 1 张；</li>
 *     <li>相册：{@link PickImagesContract}，本次最多可选张数由调用方传入（9 − 已添加）。</li>
 * </ul>
 */
public final class AiImagePicker {

    /** 选图结果与提示 */
    public interface Callback {
        /** 拍照成功（1 张）或从相册选好（可能多于可添加的张数，由图片队列截断） */
        void onImagesPicked(@NonNull List<String> uris);

        /** 需要提示的失败：没有相机权限、无法打开相机 / 相册 */
        void onPickFailed(@StringRes int message);
    }

    private static final String STATE_KEY = "ai_image_picker";
    private static final String STATE_CAMERA_URI = "camera_uri";

    private final Supplier<Context> context;
    private final Callback callback;
    private final ActivityResultLauncher<Integer> pickImages;
    private final ActivityResultLauncher<String> cameraPermission;
    private final ActivityResultLauncher<Uri> takePicture;
    /** 系统相机正在写入的文件 */
    @Nullable
    private Uri pendingCameraUri;

    /** 在 Fragment.onCreate 中调用 */
    @NonNull
    public static AiImagePicker register(@NonNull Fragment fragment, @NonNull Callback callback) {
        return new AiImagePicker(fragment, fragment, fragment::requireContext, callback);
    }

    /** 在 Activity.onCreate（super.onCreate 之后）中调用 */
    @NonNull
    public static AiImagePicker register(@NonNull ComponentActivity activity, @NonNull Callback callback) {
        return new AiImagePicker(activity, activity, () -> activity, callback);
    }

    private AiImagePicker(@NonNull ActivityResultCaller caller, @NonNull SavedStateRegistryOwner owner,
                          @NonNull Supplier<Context> context, @NonNull Callback callback) {
        this.context = context;
        this.callback = callback;
        pickImages = caller.registerForActivityResult(new PickImagesContract(), uris -> {
            if (uris == null || uris.isEmpty()) {
                return;
            }
            List<String> result = new ArrayList<>(uris.size());
            for (Uri uri : uris) {
                result.add(uri.toString());
            }
            callback.onImagesPicked(result);
        });
        cameraPermission = caller.registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (Boolean.TRUE.equals(granted)) {
                        launchCamera();
                    } else {
                        callback.onPickFailed(R.string.ai_camera_denied);
                    }
                });
        takePicture = caller.registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            Uri uri = pendingCameraUri;
            pendingCameraUri = null;
            if (uri == null) {
                return;
            }
            if (Boolean.TRUE.equals(success)) {
                List<String> result = new ArrayList<>(1);
                result.add(uri.toString());
                callback.onImagesPicked(result);
            } else {
                AiFileProvider.deleteQuietly(context.get(), uri);
            }
        });

        SavedStateRegistry registry = owner.getSavedStateRegistry();
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
    public void takePhoto() {
        if (ContextCompat.checkSelfPermission(context.get(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    /**
     * 打开系统照片选择器。
     *
     * @param maxItems 本次最多可选几张（9 − 已添加），不大于 0 时不打开
     */
    public void pickFromAlbum(int maxItems) {
        if (maxItems <= 0) {
            return;
        }
        try {
            pickImages.launch(Math.min(maxItems, AiConstants.MAX_IMAGES));
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            callback.onPickFailed(R.string.ai_album_unavailable);
        }
    }

    private void launchCamera() {
        Context ctx = context.get();
        Uri uri = null;
        try {
            uri = AiFileProvider.createCameraUri(ctx);
            pendingCameraUri = uri;
            takePicture.launch(uri);
        } catch (IOException | ActivityNotFoundException | IllegalArgumentException e) {
            pendingCameraUri = null;
            if (uri != null) {
                AiFileProvider.deleteQuietly(ctx, uri);
            }
            callback.onPickFailed(R.string.ai_camera_unavailable);
        }
    }
}
