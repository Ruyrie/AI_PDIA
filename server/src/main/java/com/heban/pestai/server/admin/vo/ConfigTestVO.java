package com.heban.pestai.server.admin.vo;

/** 配置测试结果：是否成功、说明（失败时为简短原因，不含密钥）、耗时毫秒（没有发出请求时为 0） */
public record ConfigTestVO(
        boolean ok,
        String message,
        long latencyMs) {
}
