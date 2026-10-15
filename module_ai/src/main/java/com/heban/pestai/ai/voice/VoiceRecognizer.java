package com.heban.pestai.ai.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.ai.util.AiFormat;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.network.ApiException;

import java.io.File;
import java.util.function.Consumer;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 语音识别（AskAiViewModel、ChatViewModel 各持有一个，onCleared 时 {@link #dispose()}）：
 * 上传录音（{@link AiRepository#recognizeSpeech(File)}），识别出文字先交给 ViewModel（追加到输入框、临时切到文字），
 * 再发出 {@link #getText()} 事件（页面让输入框获得焦点），
 * 没识别出文字提示「没有听清，请再说一遍」，失败提示 ApiException 的文字（断网「网络连接失败，请检查网络后重试」）。
 * <ul>
 *     <li>同时只识别一段：识别中再交来的录音直接删除；</li>
 *     <li>录音文件在识别结束、失败、取消（dispose）时都会删除；</li>
 *     <li>只在主线程调用；上传 subscribeOn(io) / observeOn(主线程)，单元测试中由 RxSchedulersRule 换成 trampoline。</li>
 * </ul>
 */
public final class VoiceRecognizer {

    private final AiRepository repository;
    /** 识别出的文字（已去掉首尾空白），在发出 {@link #getText()} 事件之前调用 */
    private final Consumer<String> onText;
    /** 提示文案（ViewModel 的 showToast） */
    private final Consumer<String> toast;

    private final MutableLiveData<Boolean> recognizing = new MutableLiveData<>(false);
    private final SingleLiveEvent<String> text = new SingleLiveEvent<>();
    @Nullable
    private Disposable task;
    private boolean disposed;

    public VoiceRecognizer(@NonNull AiRepository repository, @NonNull Consumer<String> onText,
                           @NonNull Consumer<String> toast) {
        this.repository = repository;
        this.onText = onText;
        this.toast = toast;
    }

    /** 正在识别 */
    @NonNull
    public LiveData<Boolean> getRecognizing() {
        return recognizing;
    }

    /** 识别出的文字（一次性事件，已去掉首尾空白；ViewModel 已经处理过） */
    @NonNull
    public LiveData<String> getText() {
        return text;
    }

    public boolean isRecognizing() {
        return Boolean.TRUE.equals(recognizing.getValue());
    }

    /** 上传识别；识别中或已释放时直接删除文件 */
    public void recognize(@NonNull File wav) {
        if (disposed || isRecognizing()) {
            deleteQuietly(wav);
            return;
        }
        recognizing.setValue(true);
        Disposable d = repository.recognizeSpeech(wav)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> deleteQuietly(wav))
                .subscribe(this::onRecognized, this::onFailed);
        // 同步完成（单元测试中调度器为 trampoline）时订阅已结束，不再保存
        if (!d.isDisposed()) {
            task = d;
        }
    }

    /** ViewModel onCleared：取消上传（文件随之删除） */
    public void dispose() {
        disposed = true;
        Disposable d = task;
        task = null;
        if (d != null) {
            d.dispose();
        }
    }

    private void onRecognized(@Nullable String result) {
        task = null;
        recognizing.setValue(false);
        String value = AiFormat.trim(result);
        if (value.isEmpty()) {
            toast.accept(AiConstants.TOAST_VOICE_NOT_HEARD);
        } else {
            onText.accept(value);
            text.setValue(value);
        }
    }

    private void onFailed(@NonNull Throwable error) {
        task = null;
        recognizing.setValue(false);
        toast.accept(errorMessage(error));
    }

    /**
     * 识别失败的提示：一般为 ApiException 的文字（服务器返回的 message，断网「网络连接失败，请检查网络后重试」）；
     * 服务器没有给出 message 时，40001 用「没有听清，请再说一遍」、50201 用「语音识别暂不可用，请稍后再试」
     * （ApiException 的默认文案是识图的「参数错误」「识别服务暂不可用」）。
     */
    @NonNull
    public static String errorMessage(@Nullable Throwable error) {
        ApiException e = error == null ? ApiException.of(ApiException.SERVER_ERROR) : ApiException.from(error);
        String message = e.getMessage();
        int code = e.getCode();
        if (code == ApiException.PARAM_ERROR && ApiException.defaultMessage(code).equals(message)) {
            return AiConstants.TOAST_VOICE_NOT_HEARD;
        }
        if (code == ApiException.AI_UNAVAILABLE && ApiException.defaultMessage(code).equals(message)) {
            return AiConstants.TOAST_VOICE_UNAVAILABLE;
        }
        return message == null || message.isEmpty() ? ApiException.defaultMessage(code) : message;
    }

    public static void deleteQuietly(@NonNull File file) {
        //noinspection ResultOfMethodCallIgnored 临时文件，删除失败由系统清理缓存目录
        file.delete();
    }
}
