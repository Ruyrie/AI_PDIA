package com.heban.pestai.server.admin.request;

import jakarta.validation.constraints.Size;

/** DELETE admin/api/config/{key}：密钥类必须带 admin_password */
public class ConfigResetRequest {

    @Size(max = 64, message = "管理员密码不正确")
    private String adminPassword;

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
}
