package com.heban.pestai.server.admin.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST admin/api/auth/login */
public class AdminLoginRequest {

    @NotBlank(message = "请输入用户名")
    @Size(max = 32, message = "用户名或密码错误")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(max = 64, message = "用户名或密码错误")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
