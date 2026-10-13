package com.heban.pestai.server.vo;

/**
 * 当前用户（user/me、user/profile、LoginVO.user）。avatar_url 为完整地址，可空；crops 逗号分隔，可空。
 */
public record UserVO(
        Long id,
        String phone,
        String nickname,
        String avatarUrl,
        String region,
        String identity,
        String crops) {
}
