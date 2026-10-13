package com.heban.pestai.account.data.request;

/** POST auth/register 请求体 */
public class RegisterRequest {

    private final String phone;
    private final String code;
    private final String password;

    public RegisterRequest(String phone, String code, String password) {
        this.phone = phone;
        this.code = code;
        this.password = password;
    }

    public String getPhone() {
        return phone;
    }

    public String getCode() {
        return code;
    }

    public String getPassword() {
        return password;
    }
}
