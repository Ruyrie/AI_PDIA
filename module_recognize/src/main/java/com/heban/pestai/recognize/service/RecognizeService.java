package com.heban.pestai.recognize.service;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.base.AppHost;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.recognize.data.RecognizeRepository;
import com.heban.pestai.recognize.di.RecognizeDeps;
import com.heban.pestai.recognize.model.RecognizeMode;
import com.heban.pestai.recognize.model.RecognizeState;
import com.heban.pestai.recognize.model.RecognizeState.Phase;
import com.heban.pestai.recognize.util.RecognizeErrors;

import java.io.File;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 后台执行「压缩 → 上传 → 等待识别结果」的识别服务，「先启动再绑定」混合使用：
 * <ul>
 *     <li>S02 首次进入时 startService()，任务独立于页面运行，按 Home 键、页面解绑都不会中断；</li>
 *     <li>S02 每次 onStart 绑定、onStop 解绑，通过 {@link RecognizeBinder} 注册进度监听，绑定后立即回放最新快照；</li>
 *     <li>任务结束（页面已处理终态）或取消时 stopSelf()，页面解绑后销毁。</li>
 * </ul>
 * 状态只在主线程修改与发布；一次识别通常 30 秒内结束，不使用前台服务。
 */
public class RecognizeService extends Service {

    private static final String TAG = "RecognizeService";
    /** 分析阶段补进度的周期 */
    private static final long ANALYZE_TICK_MS = 300;

    private final RecognizeBinder binder = new RecognizeBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CompositeDisposable task = new CompositeDisposable();
    @Nullable
    private Disposable analyzeTimer;
    @NonNull
    private RecognizeState state = RecognizeState.idle();
    @Nullable
    private ProgressListener listener;
    @Nullable
    private File compressedFile;
    private RecognizeRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = AppHost.deps(this, RecognizeDeps.class).recognizeRepository();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (state.getPhase().isRunning()) {
            // 同一时间只处理一个任务
            Log.w(TAG, "已有识别任务在执行，忽略新的启动请求");
            return START_NOT_STICKY;
        }
        // 上一个任务已结束但服务尚未销毁（页面还没解绑）时，开始新任务，避免新页面收到旧结果
        task.clear();
        disposeTimer();
        RecognizeRepository.deleteQuietly(compressedFile);
        compressedFile = null;
        String imageUri = intent == null ? null : intent.getStringExtra(RouterPath.EXTRA_IMAGE_URI);
        RecognizeMode mode = RecognizeMode.of(intent == null ? null : intent.getStringExtra(RouterPath.EXTRA_MODE));
        if (TextUtils.isEmpty(imageUri)) {
            publish(RecognizeState.failed(state, ApiException.NETWORK, FailReason.IMAGE_BAD, "缺少照片地址"));
        } else {
            start(Uri.parse(imageUri), mode);
        }
        // 被系统杀死后不自动重启；页面重建后绑定到空闲的新 Service，按「识别已中断」处理
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        task.dispose();
        disposeTimer();
        RecognizeRepository.deleteQuietly(compressedFile);
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------- 识别流程 ----------

    @MainThread
    private void start(@NonNull Uri source, @NonNull RecognizeMode mode) {
        publish(RecognizeState.compressing());
        task.add(Single.<ImageCompressor.CompressResult>create(emitter -> {
                    // 取消时 io 线程被中断，迟到的异常用 tryOnError 丢弃，不交给全局错误处理器
                    try {
                        emitter.onSuccess(repository.compress(source));
                    } catch (Throwable t) {
                        emitter.tryOnError(t);
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(result -> {
                    compressedFile = result.file;
                    publish(RecognizeState.uploading(result.originalSize, result.compressedSize, 0));
                    return repository.recognize(result.file, mode,
                            percent -> mainHandler.post(() -> onUploadProgress(percent)));
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onSuccess, this::onFailure));
    }

    /** 上传百分比（主线程）：0–99 映射为总进度 10–70%，到 100 进入 AI 分析阶段 */
    @MainThread
    private void onUploadProgress(int percent) {
        if (state.getPhase() != Phase.UPLOADING) {
            return;
        }
        if (percent >= 100) {
            startAnalyzing();
        } else if (percent > state.getUploadPercent()) {
            publish(RecognizeState.uploading(state.getOriginalSize(), state.getCompressedSize(), percent));
        }
    }

    /** 服务端不返回分析进度：从 70% 起每 300ms 加 1，最多到 95%，结果返回后直接到 100% */
    @MainThread
    private void startAnalyzing() {
        publish(RecognizeState.analyzing(state.getOriginalSize(), state.getCompressedSize(),
                RecognizeState.PROGRESS_ANALYZE_START));
        if (analyzeTimer != null) {
            return;
        }
        analyzeTimer = Observable.interval(ANALYZE_TICK_MS, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                .subscribe(tick -> {
                    RecognizeState next = state.nextAnalyzeTick();
                    if (next != state) {
                        publish(next);
                    } else {
                        disposeTimer();
                    }
                });
    }

    @MainThread
    private void onSuccess(@NonNull RecognitionVO result) {
        disposeTimer();
        RecognizeRepository.deleteQuietly(compressedFile);
        compressedFile = null;
        notifyRecordChanged();
        publish(RecognizeState.success(state.getOriginalSize(), state.getCompressedSize(), result));
    }

    @MainThread
    private void onFailure(@NonNull Throwable error) {
        disposeTimer();
        RecognizeRepository.deleteQuietly(compressedFile);
        compressedFile = null;
        int code = RecognizeErrors.codeOf(error);
        String reason = RecognizeErrors.reasonOf(error);
        Log.w(TAG, "识别失败 code=" + code + " reason=" + reason, error);
        // 服务器写入失败记录（照片不清晰、识别失败）后也要通知首页和识别记录刷新
        if (code > 0 && !RecognizeErrors.isUnauthorized(code)) {
            notifyRecordChanged();
        }
        String message = error instanceof ImageCompressor.ImageReadException
                ? error.getMessage() : ApiException.messageOf(error);
        publish(RecognizeState.failed(state, code, reason, message));
    }

    private void notifyRecordChanged() {
        try {
            ServiceHub.get(IRecordService.class).notifyChanged();
        } catch (IllegalStateException e) {
            // 组件单独运行时没有注册 IRecordService，跳过通知
            Log.w(TAG, "IRecordService 未注册，跳过通知");
        }
    }

    private void disposeTimer() {
        if (analyzeTimer != null) {
            analyzeTimer.dispose();
            analyzeTimer = null;
        }
    }

    /** 保存最新快照并通知监听者（页面不可见时只保存，回来绑定时回放） */
    private void publish(@NonNull RecognizeState newState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> publish(newState));
            return;
        }
        state = newState;
        if (listener != null) {
            listener.onStateChanged(newState);
        }
    }

    /**
     * 同进程本地绑定，不需要 AIDL。页面通过 {@link RecognizeTask} 接口使用。
     */
    public final class RecognizeBinder extends Binder implements RecognizeTask {

        @Override
        public void attach(@NonNull ProgressListener progressListener) {
            listener = progressListener;
            progressListener.onStateChanged(state);
        }

        @Override
        public void detach() {
            listener = null;
        }

        @Override
        public void cancel() {
            if (!state.getPhase().isTerminal()) {
                task.clear();
                disposeTimer();
                RecognizeRepository.deleteQuietly(compressedFile);
                compressedFile = null;
                publish(RecognizeState.canceled(state));
            }
            stopSelf();
        }

        @Override
        public void finishTask() {
            stopSelf();
        }
    }
}
