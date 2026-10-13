package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.heban.pestai.server.common.Identity;

/**
 * PUT user/profile。avatar_url 可空（为空时保留原头像；非空时必须是本服务器 user/avatar 返回的地址）；
 * region 为空时存空字符串；crops 为逗号分隔字符串，可空。
 */
public class ProfileRequest {

    @NotBlank(message = "请填写昵称")
    @Size(max = 16, message = "昵称最多 16 个字")
    private String nickname;

    @Size(max = 255)
    private String avatarUrl;

    @Size(max = 64, message = "地区最多 64 个字")
    private String region;

    @NotBlank(message = "请选择种植身份")
    @Pattern(regexp = Identity.REGEX, message = "种植身份只能是种植户、家庭园艺或农技人员")
    private String identity;

    @Size(max = 255, message = "主要作物太多了")
    private String crops;

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
}
