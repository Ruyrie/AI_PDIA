package com.heban.pestai.account.util;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * 验证码 60 秒倒计时（RxJava intervalRange，computation 线程计时，主线程更新 LiveData）。
 * 放在 ViewModel 中持有，旋转屏幕不中断；ViewModel 的 onCleared() 中调用 {@link #stop()}。
 */
public class SmsCountdown {

    public static final int DEFAULT_SECONDS = 60;
    public static final String TEXT_GET_CODE = "获取验证码";

    private final int seconds;
    private final MutableLiveData<Boolean> counting = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> remaining = new MutableLiveData<>(0);
    private final MutableLiveData<String> text = new MutableLiveData<>(TEXT_GET_CODE);
    private Disposable disposable;

    public SmsCountdown() {
        this(DEFAULT_SECONDS);
    }

    public SmsCountdown(int seconds) {
        this.seconds = seconds;
    }

    /** 「获取验证码」/「{N}s 后重发」 */
    @NonNull
    public static String textOf(int remaining) {
        return remaining > 0 ? remaining + "s 后重发" : TEXT_GET_CODE;
    }

    /** 开始（或重新开始）倒计时：立即显示「60s 后重发」，之后每秒减一，到 0 恢复「获取验证码」 */
    public void start() {
        stop();
        onTick(seconds);
        disposable = Observable.intervalRange(1, seconds, 1, 1, TimeUnit.SECONDS)
                .map(n -> seconds - n.intValue())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onTick);
    }

    /** 停止并恢复初始状态 */
    public void stop() {
        if (disposable != null) {
            disposable.dispose();
            disposable = null;
        }
        if (Boolean.TRUE.equals(counting.getValue())) {
            onTick(0);
        }
    }

    private void onTick(int left) {
        remaining.setValue(left);
        counting.setValue(left > 0);
        text.setValue(textOf(left));
    }

    @NonNull
    public LiveData<Boolean> getCounting() {
        return counting;
    }

    @NonNull
    public LiveData<Integer> getRemaining() {
        return remaining;
    }

    @NonNull
    public LiveData<String> getText() {
        return text;
    }

    public boolean isCounting() {
        return Boolean.TRUE.equals(counting.getValue());
    }
}
