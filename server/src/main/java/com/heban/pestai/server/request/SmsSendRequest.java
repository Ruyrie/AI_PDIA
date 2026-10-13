package com.heban.pestai.server.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.common.SmsScene;

/**
 * POST auth/sms/send；scene 传入其他值时 Jackson 反序列化失败，同样返回 40001。
 * scene 只接受 LOGIN / REGISTER / RESET / REBIND：CHANGE_PHONE、DEACTIVATE、RECOVERY 必须走需要登录的接口，这里按参数错误拒绝。
 */
public class SmsSendRequest {

    @NotBlank
    @Pattern(regexp = Rules.PHONE_REGEX, message = Rules.PHONE_MESSAGE)
    private String phone;

    @NotNull
    private SmsScene scene;

    /** 未写 message：校验失败时统一返回「参数错误」，与传入未知场景一致 */
    @AssertTrue
    public boolean isScenePublic() {
        return scene == null || scene.isPublic();
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public SmsScene getScene() {
        return scene;
    }

    public void setScene(SmsScene scene) {
        this.scene = scene;
    }
}
