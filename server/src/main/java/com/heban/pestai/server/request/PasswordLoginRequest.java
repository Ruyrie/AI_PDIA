package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.heban.pestai.server.common.Rules;

/** POST auth/login/password；登录时不校验密码强度，只防止超长输入 */
public class PasswordLoginRequest {

    @NotBlank
    @Pattern(regexp = Rules.PHONE_REGEX, message = Rules.PHONE_MESSAGE)
    private String phone;

    @NotBlank
    @Size(max = 20)
    private String password;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
