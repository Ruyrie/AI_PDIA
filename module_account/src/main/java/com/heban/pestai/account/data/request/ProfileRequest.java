package com.heban.pestai.account.data.request;

/**
 * PUT user/profile 请求体。avatarUrl 为 null 时 Gson 不序列化该字段，服务器保留原头像；
 * crops 为逗号分隔字符串（如 番茄,黄瓜,小麦），一个都没选时为空串。
 * A06 已不再收集所在地区（产品负责人决定），请求体不含 region 字段，服务器保留原值（新用户为空串）。
 */
public class ProfileRequest {

    private final String nickname;
    private final String avatarUrl;
    private final String identity;
    private final String crops;

    public ProfileRequest(String nickname, String avatarUrl, String identity, String crops) {
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
        this.identity = identity;
        this.crops = crops;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getIdentity() {
        return identity;
    }

    public String getCrops() {
        return crops;
    }
}
