package com.heban.pestai.server.admin.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.heban.pestai.server.common.Identity;

/**
 * PUT admin/api/users/{id}/profile。
 * <ul>
 *   <li>identity：未给出或 null → 不修改；""（空串）→ 清除（存 NULL，显示「未设置」）；种植户 / 家庭园艺 / 农技人员 → 修改；
 *       其他值（含只有空格）→ 40001；</li>
 *   <li>crops：未给出或 null → 不修改；"" → 清空；其他按逗号（含中文逗号、顿号）拆分规范后保存；</li>
 *   <li>clear_avatar 为 true 时清除头像（事务提交后删除头像文件）。</li>
 * </ul>
 */
public class AdminProfileRequest {

    @NotBlank(message = "请填写昵称")
    @Size(max = 16, message = "昵称最多 16 个字")
    private String nickname;

    @Pattern(regexp = "(" + Identity.REGEX + ")?", message = "种植身份只能是种植户、家庭园艺或农技人员")
    private String identity;

    @Size(max = 255, message = "主要作物太多了")
    private String crops;

    private Boolean clearAvatar;

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
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

    public Boolean getClearAvatar() {
        return clearAvatar;
    }

    public void setClearAvatar(Boolean clearAvatar) {
        this.clearAvatar = clearAvatar;
    }
}
