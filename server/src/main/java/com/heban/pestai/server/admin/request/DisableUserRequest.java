package com.heban.pestai.server.admin.request;

import jakarta.validation.constraints.Size;

/** POST admin/api/users/{id}/disable：停用原因，可空 */
public class DisableUserRequest {

    @Size(max = 200, message = "停用原因最多 200 个字")
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
