package com.heban.pestai.recognize.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.RecognitionVO;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * RecognizeService 传给 S02 的不可变进度快照。
 *
 * <table>
 *     <tr><th>阶段</th><th>总进度</th><th>步骤</th></tr>
 *     <tr><td>COMPRESSING</td><td>0</td><td>压缩 RUNNING，其余 WAITING</td></tr>
 *     <tr><td>UPLOADING</td><td>10 + 上传百分比 × 0.6（10–70）</td><td>压缩 DONE，上传 RUNNING</td></tr>
 *     <tr><td>ANALYZING</td><td>70 起每 300ms +1，最多 95</td><td>压缩、上传 DONE，分析 RUNNING</td></tr>
 *     <tr><td>SUCCESS</td><td>100</td><td>全部 DONE</td></tr>
 * </table>
 */
public final class RecognizeState {

    public enum Phase {
        /** 没有任务（新建的 Service；进程被回收后重新绑定时即为此状态） */
        IDLE,
        COMPRESSING,
        UPLOADING,
        ANALYZING,
        SUCCESS,
        FAILED,
        CANCELED;

        public boolean isRunning() {
            return this == COMPRESSING || this == UPLOADING || this == ANALYZING;
        }

        public boolean isTerminal() {
            return this == SUCCESS || this == FAILED || this == CANCELED;
        }
    }

    public static final int PROGRESS_UPLOAD_START = 10;
    public static final int PROGRESS_ANALYZE_START = 70;
    public static final int PROGRESS_ANALYZE_MAX = 95;
    public static final int PROGRESS_DONE = 100;

    private static final RecognizeState IDLE = new RecognizeState(Phase.IDLE, 0, 0, -1, -1, null, 0, null, null);

    @NonNull
    private final Phase phase;
    private final int progress;
    private final int uploadPercent;
    private final long originalSize;
    private final long compressedSize;
    @Nullable
    private final RecognitionVO result;
    private final int errorCode;
    @Nullable
    private final String errorMessage;
    @Nullable
    private final String failReason;

    private RecognizeState(@NonNull Phase phase, int progress, int uploadPercent, long originalSize,
                           long compressedSize, @Nullable RecognitionVO result, int errorCode,
                           @Nullable String errorMessage, @Nullable String failReason) {
        this.phase = phase;
        this.progress = progress;
        this.uploadPercent = uploadPercent;
        this.originalSize = originalSize;
        this.compressedSize = compressedSize;
        this.result = result;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.failReason = failReason;
    }

    // ---------- 工厂方法 ----------

    @NonNull
    public static RecognizeState idle() {
        return IDLE;
    }

    @NonNull
    public static RecognizeState compressing() {
        return new RecognizeState(Phase.COMPRESSING, 0, 0, -1, -1, null, 0, null, null);
    }

    @NonNull
    public static RecognizeState uploading(long originalSize, long compressedSize, int uploadPercent) {
        int percent = clamp(uploadPercent, 0, 100);
        return new RecognizeState(Phase.UPLOADING, uploadProgress(percent), percent, originalSize, compressedSize,
                null, 0, null, null);
    }

    @NonNull
    public static RecognizeState analyzing(long originalSize, long compressedSize, int progress) {
        return new RecognizeState(Phase.ANALYZING,
                clamp(progress, PROGRESS_ANALYZE_START, PROGRESS_ANALYZE_MAX), 100,
                originalSize, compressedSize, null, 0, null, null);
    }

    @NonNull
    public static RecognizeState success(long originalSize, long compressedSize, @NonNull RecognitionVO result) {
        return new RecognizeState(Phase.SUCCESS, PROGRESS_DONE, 100, originalSize, compressedSize, result,
                0, null, null);
    }

    /**
     * @param errorCode  ApiException 的错误码（本机异常为 -1 / -2）
     * @param failReason TIMEOUT / UNAVAILABLE / IMAGE_BAD / NETWORK
     */
    @NonNull
    public static RecognizeState failed(@NonNull RecognizeState previous, int errorCode,
                                        @NonNull String failReason, @Nullable String errorMessage) {
        return new RecognizeState(Phase.FAILED, previous.progress, previous.uploadPercent, previous.originalSize,
                previous.compressedSize, null, errorCode, errorMessage, failReason);
    }

    @NonNull
    public static RecognizeState canceled(@NonNull RecognizeState previous) {
        return new RecognizeState(Phase.CANCELED, previous.progress, previous.uploadPercent, previous.originalSize,
                previous.compressedSize, null, 0, null, null);
    }

    /** 分析阶段的一次计时：总进度 +1，最多 95；不在分析阶段时原样返回 */
    @NonNull
    public RecognizeState nextAnalyzeTick() {
        if (phase != Phase.ANALYZING || progress >= PROGRESS_ANALYZE_MAX) {
            return this;
        }
        return analyzing(originalSize, compressedSize, progress + 1);
    }

    // ---------- 进度换算（纯 Java，便于单元测试） ----------

    /** 上传百分比 0–100 → 总进度 10–70（10 + 百分比 × 0.6，取整） */
    public static int uploadProgress(int uploadPercent) {
        int percent = clamp(uploadPercent, 0, 100);
        return PROGRESS_UPLOAD_START + percent * 6 / 10;
    }

    /** 三个步骤（压缩、上传、AI 分析）的状态 */
    @NonNull
    public List<StepState> steps() {
        switch (phase) {
            case COMPRESSING:
                return list(StepState.RUNNING, StepState.WAITING, StepState.WAITING);
            case UPLOADING:
                return list(StepState.DONE, StepState.RUNNING, StepState.WAITING);
            case ANALYZING:
                return list(StepState.DONE, StepState.DONE, StepState.RUNNING);
            case SUCCESS:
                return list(StepState.DONE, StepState.DONE, StepState.DONE);
            default:
                return list(StepState.WAITING, StepState.WAITING, StepState.WAITING);
        }
    }

    private static List<StepState> list(StepState a, StepState b, StepState c) {
        return Collections.unmodifiableList(Arrays.asList(a, b, c));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------- getter ----------

    @NonNull
    public Phase getPhase() {
        return phase;
    }

    public int getProgress() {
        return progress;
    }

    public int getUploadPercent() {
        return uploadPercent;
    }

    public long getOriginalSize() {
        return originalSize;
    }

    public long getCompressedSize() {
        return compressedSize;
    }

    @Nullable
    public RecognitionVO getResult() {
        return result;
    }

    public int getErrorCode() {
        return errorCode;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    @Nullable
    public String getFailReason() {
        return failReason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RecognizeState that = (RecognizeState) o;
        return progress == that.progress
                && uploadPercent == that.uploadPercent
                && originalSize == that.originalSize
                && compressedSize == that.compressedSize
                && errorCode == that.errorCode
                && phase == that.phase
                && Objects.equals(result, that.result)
                && Objects.equals(errorMessage, that.errorMessage)
                && Objects.equals(failReason, that.failReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phase, progress, uploadPercent, originalSize, compressedSize, result, errorCode,
                errorMessage, failReason);
    }

    @NonNull
    @Override
    public String toString() {
        return "RecognizeState{" + phase + ", progress=" + progress + ", upload=" + uploadPercent
                + ", code=" + errorCode + ", reason=" + failReason + '}';
    }
}
