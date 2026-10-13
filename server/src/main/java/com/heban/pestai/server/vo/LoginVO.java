package com.heban.pestai.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 登录 / 注册结果。is_new 用包装类型并显式指定 JSON 名，避免被识别为属性 new。
 */
public record LoginVO(
        String token,
        UserVO user,
        @JsonProperty("is_new") Boolean isNew) {
}
