package com.heban.pestai.server.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.heban.pestai.server.common.RecoverMethod;
import com.heban.pestai.server.common.Rules;

/**
 * POST auth/recover/verify：原手机号 + 验证方式。method 为 PASSWORD 时必填 password（不校验强度，只防超长输入），
 * 为 RECOVERY_CODE 时必填 recovery_code（大小写、空格、连字符不限）；另一项忽略。
 * method 传入其他值时 Jackson 反序列化失败，返回 40001。
 */
public class RecoverVerifyRequest {

    @NotBlank
    @Pattern(regexp = Rules.PHONE_REGEX, message = "原手机号格式不正确")
    private String oldPhone;

    @NotNull(message = "请选择验证方式")
    private RecoverMethod method;

    @Size(max = 20)
    private String password;

    @Size(max = 64)
    private String recoveryCode;

    @AssertTrue(message = "请输入登录密码")
    public boolean isPasswordProvided() {
        return method != RecoverMethod.PASSWORD || (password != null && !password.isBlank());
    }

    @AssertTrue(message = "请输入恢复码")
    public boolean isRecoveryCodeProvided() {
        return method != RecoverMethod.RECOVERY_CODE || (recoveryCode != null && !recoveryCode.isBlank());
    }

    public String getOldPhone() {
        return oldPhone;
    }

    public void setOldPhone(String oldPhone) {
        this.oldPhone = oldPhone;
    }

    public RecoverMethod getMethod() {
        return method;
    }

    public void setMethod(RecoverMethod method) {
        this.method = method;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRecoveryCode() {
        return recoveryCode;
    }

    public void setRecoveryCode(String recoveryCode) {
        this.recoveryCode = recoveryCode;
    }
}
