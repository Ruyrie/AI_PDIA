package com.heban.pestai.server.admin.vo;

/** 重置用户密码：12 位临时密码（字母 + 数字），只在这次响应中返回 */
public record TempPasswordVO(String tempPassword) {
}
