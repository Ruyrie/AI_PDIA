package com.heban.pestai.account.data.request;

/** POST auth/password/reset 请求体：JSON 字段 phone、reset_ticket、new_password */
public class PasswordResetRequest {

    private final String phone;
    private final String resetTicket;
    private final String newPassword;

    public PasswordResetRequest(String phone, String resetTicket, String newPassword) {
        this.phone = phone;
        this.resetTicket = resetTicket;
        this.newPassword = newPassword;
    }

    public String getPhone() {
        return phone;
    }

    public String getResetTicket() {
        return resetTicket;
    }

    public String getNewPassword() {
        return newPassword;
    }
}
