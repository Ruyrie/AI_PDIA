package com.heban.pestai.recognize.service;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.heban.pestai.recognize.model.RecognizeState;

/**
 * RecognizeService → 页面的进度回调，始终在主线程回调。
 */
public interface ProgressListener {

    @MainThread
    void onStateChanged(@NonNull RecognizeState state);
}
