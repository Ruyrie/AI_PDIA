package com.heban.pestai.account.data.request;

/** POST auth/login/sms 请求体 */
public class SmsLoginRequest {

    private final String phone;
    private final String code;

    public SmsLoginRequest(String phone, String code) {
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
