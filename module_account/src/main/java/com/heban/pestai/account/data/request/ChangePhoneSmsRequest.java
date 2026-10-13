package com.heban.pestai.account.data.request;

/** POST user/phone/sms 请求体：JSON 字段 new_phone（验证码发往新手机号，CHANGE_PHONE 场景） */
public class ChangePhoneSmsRequest {

    private final String newPhone;

    public ChangePhoneSmsRequest(String newPhone) {
        this.newPhone = newPhone;
    }

    public String getNewPhone() {
        return newPhone;
    }
}
