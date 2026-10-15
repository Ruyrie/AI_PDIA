package com.heban.pestai.server.admin.request;

import jakarta.validation.constraints.NotBlank;

/** POST admin/api/users/{id}/deactivate：confirm_phone 必须等于该用户的完整手机号 */
public class DeactivateUserRequest {

    @NotBlank(message = "请输入该用户的完整手机号")
    private String confirmPhone;

    public String getConfirmPhone() {
        return confirmPhone;
    }

    public void setConfirmPhone(String confirmPhone) {
        this.confirmPhone = confirmPhone;
    }
}
