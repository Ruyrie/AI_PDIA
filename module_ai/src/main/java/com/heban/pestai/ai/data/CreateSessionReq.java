package com.heban.pestai.ai.data;

import androidx.annotation.Nullable;

/**
 * POST ai/sessions 请求体。recordId 为空时 Gson 不输出该字段，请求体为 {}。
 */
public class CreateSessionReq {

    /** JSON：record_id */
    @Nullable
    private final Long recordId;

    public CreateSessionReq(@Nullable Long recordId) {
        this.recordId = recordId;
    }

    @Nullable
    public Long getRecordId() {
        return recordId;
    }
}
