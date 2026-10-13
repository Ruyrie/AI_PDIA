package com.heban.pestai.record.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.vo.RecordItemVO;

/**
 * HistoryViewModel 发出的一次性导航事件（SingleLiveEvent）。
 * <ul>
 *     <li>OPEN_RESULT：成功记录 → R01（SCAN_RESULT + EXTRA_RECORD_ID）</li>
 *     <li>OPEN_ERROR：失败记录 → R02（SCAN_ERROR + EXTRA_RECORD_ID、EXTRA_ERROR_TYPE、EXTRA_FAIL_REASON、EXTRA_IMAGE_URL、EXTRA_MODE）</li>
 *     <li>OPEN_SCAN：顶栏拍照 /「去拍照识别」→ S01（SCAN + EXTRA_MODE）</li>
 * </ul>
 */
public final class HistoryEvent {

    public static final int OPEN_RESULT = 1;
    public static final int OPEN_ERROR = 2;
    public static final int OPEN_SCAN = 3;

    private final int type;
    private final long recordId;
    @Nullable
    private final RecordItemVO record;
    @Nullable
    private final String errorType;
    @Nullable
    private final String mode;

    private HistoryEvent(int type, long recordId, @Nullable RecordItemVO record, @Nullable String errorType,
                         @Nullable String mode) {
        this.type = type;
        this.recordId = recordId;
        this.record = record;
        this.errorType = errorType;
        this.mode = mode;
    }

    @NonNull
    static HistoryEvent openResult(long recordId) {
        return new HistoryEvent(OPEN_RESULT, recordId, null, null, null);
    }

    /** error_type 取 status：IMAGE_BAD → IMAGE_BAD，其余 → FAILED；mode 取记录的 mode */
    @NonNull
    static HistoryEvent openError(@NonNull RecordItemVO record) {
        String errorType = RecognitionStatus.IMAGE_BAD.equals(record.getStatus()) ? ErrorType.IMAGE_BAD : ErrorType.FAILED;
        return new HistoryEvent(OPEN_ERROR, record.getId(), record, errorType, record.getMode());
    }

    @NonNull
    static HistoryEvent openScan(@NonNull String mode) {
        return new HistoryEvent(OPEN_SCAN, 0, null, null, mode);
    }

    public int getType() {
        return type;
    }

    public long getRecordId() {
        return recordId;
    }

    /** OPEN_ERROR 时为被点按的记录 */
    @Nullable
    public RecordItemVO getRecord() {
        return record;
    }

    /** OPEN_ERROR 时为 ErrorType.IMAGE_BAD / FAILED */
    @Nullable
    public String getErrorType() {
        return errorType;
    }

    /** OPEN_ERROR：记录的 mode；OPEN_SCAN：S01 预选的模式 */
    @Nullable
    public String getMode() {
        return mode;
    }
}
