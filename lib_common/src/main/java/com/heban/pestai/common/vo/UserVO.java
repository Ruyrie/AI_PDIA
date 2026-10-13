package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 当前用户（user/me、user/profile；LoginVO.user）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class UserVO {

    /** 用户 ID */
    private long id;
    /** 手机号，界面显示时脱敏 */
    private String phone;
    /** 昵称，最多 16 字 */
    private String nickname;
    /** 头像地址，可空；空时显示昵称首字 */
    private String avatarUrl;
    /** 地区，如 江苏省 南京市 江宁区 */
    private String region;
    /** 种植身份：种植户 / 家庭园艺 / 农技人员（见 Identity） */
    private String identity;
    /** 主要作物，逗号分隔，可空 */
    private String crops;

    public UserVO() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public String getCrops() {
        return crops;
    }

    public void setCrops(String crops) {
        this.crops = crops;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UserVO that = (UserVO) o;
        return id == that.id
                && Objects.equals(phone, that.phone)
                && Objects.equals(nickname, that.nickname)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(region, that.region)
                && Objects.equals(identity, that.identity)
                && Objects.equals(crops, that.crops);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, phone, nickname, avatarUrl, region, identity, crops);
    }
}
