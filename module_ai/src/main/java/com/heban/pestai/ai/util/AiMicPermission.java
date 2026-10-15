package com.heban.pestai.ai.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.function.Supplier;

/**
 * Q01 / Q02 输入栏语音输入的麦克风权限（RECORD_AUDIO）。与 {@link AiImagePicker} 相同，必须在宿主 onCreate 中创建
 * （ActivityResult 需在 STARTED 之前注册），再传给 ComposerBar；ComposerBar 设置结果监听，交给 ViewModel 处理
 * （ViewModel 记得是哪次申请：旋转屏幕后授权结果仍能正确处理）。
 * <ul>
 *     <li>文字输入时点按麦克风图标、没有权限：{@link #request()} 弹出系统授权框，授权后切到语音，拒绝时留在文字并提示；</li>
 *     <li>语音输入时按住「按住 说话」、没有权限（如在系统设置中撤销了）：同样申请，本次不录音，授权后提示重新按住；</li>
 *     <li>授权框显示期间再次申请直接忽略（同时只能有一个权限请求）。</li>
 * </ul>
 */
public final class AiMicPermission {

    /** 授权结果 */
    public interface Listener {
        void onResult(boolean granted);
    }

    private final Supplier<Context> context;
    private final ActivityResultLauncher<String> launcher;
    @Nullable
    private Listener listener;
    private boolean requesting;

    /** 在 Fragment.onCreate 中调用 */
    @NonNull
    public static AiMicPermission register(@NonNull Fragment fragment) {
        return new AiMicPermission(fragment, fragment::requireContext);
    }

    /** 在 Activity.onCreate（super.onCreate 之后）中调用 */
    @NonNull
    public static AiMicPermission register(@NonNull ComponentActivity activity) {
        return new AiMicPermission(activity, () -> activity);
    }

    private AiMicPermission(@NonNull ActivityResultCaller caller, @NonNull Supplier<Context> context) {
        this.context = context;
        this.launcher = caller.registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> {
                    requesting = false;
                    if (listener != null) {
                        listener.onResult(Boolean.TRUE.equals(granted));
                    }
                });
    }

    /** 授权结果监听（ComposerBar 创建时设置、释放时清除；结果在宿主 STARTED 之后才回调） */
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public boolean isGranted() {
        return ContextCompat.checkSelfPermission(context.get(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 弹出系统授权框（已在申请中时忽略） */
    public void request() {
        if (requesting) {
            return;
        }
        requesting = true;
        try {
            launcher.launch(Manifest.permission.RECORD_AUDIO);
        } catch (IllegalStateException e) {
            // 宿主已销毁等情况：不申请
            requesting = false;
        }
    }
}
