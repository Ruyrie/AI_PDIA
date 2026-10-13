package com.heban.pestai.account.data.request;

/** POST auth/sms/send 请求体：phone、scene（LOGIN / REGISTER / RESET / REBIND，见 SmsScene） */
public class SmsSendRequest {

    private final String phone;
    private final String scene;

    public SmsSendRequest(String phone, String scene) {
        this.phone = phone;
        this.scene = scene;
    }

    public String getPhone() {
        return phone;
    }

    public String getScene() {
        return scene;
    }
}
