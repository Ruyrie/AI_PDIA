package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 登录 / 注册结果（auth/login/password、auth/login/sms、auth/register）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class LoginVO {

    /** JWT，有效期 30 天 */
    private String token;
    /** 当前用户 */
    private UserVO user;
    /** 验证码登录时是否为新注册用户 */
    private boolean isNew;

    public LoginVO() {
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public UserVO getUser() {
        return user;
    }

    public void setUser(UserVO user) {
        this.user = user;
    }

    public boolean isNew() {
        return isNew;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LoginVO that = (LoginVO) o;
        return Objects.equals(token, that.token)
                && Objects.equals(user, that.user)
                && isNew == that.isNew;
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, user, isNew);
    }
}
