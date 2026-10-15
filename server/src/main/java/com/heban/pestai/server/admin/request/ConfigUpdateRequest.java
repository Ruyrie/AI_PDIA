package com.heban.pestai.server.admin.request;

import jakarta.validation.constraints.Size;

/**
 * PUT admin/api/config/{key}：value 去掉首尾空白后不能为空；密钥类必须带 admin_password（当前管理员密码）。
 */
public class ConfigUpdateRequest {

    @Size(max = 2000, message = "配置值太长了")
    private String value;

    @Size(max = 64, message = "管理员密码不正确")
    private String adminPassword;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
}
