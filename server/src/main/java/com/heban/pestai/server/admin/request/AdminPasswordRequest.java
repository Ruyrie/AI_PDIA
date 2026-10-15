package com.heban.pestai.server.admin.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST admin/api/auth/password：新密码至少 10 位、同时包含字母和数字、不能与原密码相同（由 AdminAuthService 校验）。
 */
public class AdminPasswordRequest {

    @NotBlank(message = "请输入原密码")
    @Size(max = 64, message = "原密码不正确")
    private String oldPassword;

    @NotBlank(message = "请输入新密码")
    private String newPassword;

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
