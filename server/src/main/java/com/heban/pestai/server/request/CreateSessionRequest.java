package com.heban.pestai.server.request;

import jakarta.validation.constraints.Positive;

/** POST ai/sessions：record_id 可空，追问识别结果时传 */
public class CreateSessionRequest {

    @Positive
    private Long recordId;

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }
}
