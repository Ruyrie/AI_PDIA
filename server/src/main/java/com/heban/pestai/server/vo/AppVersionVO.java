package com.heban.pestai.server.vo;

/** 最新版本信息（预留，v1.0 App 不调用）。 */
public record AppVersionVO(
        Integer versionCode,
        String versionName,
        String downloadUrl,
        Boolean forceUpdate,
        String releaseNote) {
}
