package com.heban.pestai.base;

import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * 所有 ViewModel 的父类：统一管理 RxJava 订阅和一次性提示。
 * 页面内所有订阅都通过 {@link #add(Disposable)} 加入 disposables，页面销毁时统一取消。
 */
public abstract class BaseViewModel extends ViewModel {

    protected final CompositeDisposable disposables = new CompositeDisposable();

    /** 一次性提示文案，页面以 Snackbar 显示 */
    protected final SingleLiveEvent<String> toast = new SingleLiveEvent<>();

    public LiveData<String> getToast() {
        return toast;
    }

    protected void add(Disposable disposable) {
        disposables.add(disposable);
    }

    /** 发出一次性提示；可在任意线程调用 */
    protected void showToast(String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            toast.setValue(message);
        } else {
            toast.postValue(message);
        }
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
