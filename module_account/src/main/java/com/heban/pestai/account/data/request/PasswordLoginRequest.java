package com.heban.pestai.account.data.request;

/** POST auth/login/password 请求体 */
public class PasswordLoginRequest {

    private final String phone;
    private final String password;

    public PasswordLoginRequest(String phone, String password) {
        this.phone = phone;
        this.password = password;
    }

    public String getPhone() {
        return phone;
    }

    public String getPassword() {
        return password;
    }
}
