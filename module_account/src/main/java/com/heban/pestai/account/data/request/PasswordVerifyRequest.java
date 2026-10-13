package com.heban.pestai.account.data.request;

/** POST auth/password/verify 请求体 */
public class PasswordVerifyRequest {

    private final String phone;
    private final String code;

    public PasswordVerifyRequest(String phone, String code) {
        this.phone = phone;
        this.code = code;
    }

    public String getPhone() {
        return phone;
    }

    public String getCode() {
        return code;
    }
}
