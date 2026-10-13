package com.heban.pestai.account.data.request;

/** PUT user/phone 请求体：JSON 字段 new_phone、code */
public class ChangePhoneRequest {

    private final String newPhone;
    private final String code;

    public ChangePhoneRequest(String newPhone, String code) {
        this.newPhone = newPhone;
        this.code = code;
    }

    public String getNewPhone() {
        return newPhone;
    }

    public String getCode() {
        return code;
    }
}
