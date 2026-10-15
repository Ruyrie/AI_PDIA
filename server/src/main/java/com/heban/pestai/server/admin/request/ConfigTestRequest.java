package com.heban.pestai.server.admin.request;

/** POST admin/api/config/test/{group}：sms 时为接收测试验证码的手机号 */
public class ConfigTestRequest {

    private String phone;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
