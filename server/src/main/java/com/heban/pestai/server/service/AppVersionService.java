package com.heban.pestai.server.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.config.PestaiProperties;
import com.heban.pestai.server.entity.AppVersion;
import com.heban.pestai.server.mapper.AppVersionMapper;
import com.heban.pestai.server.vo.AppVersionVO;

/**
 * 查询最新版本（预留，v1.0 App 不调用）：取 version_code 最大的一条；客户端版本与最新版本之间存在强制更新版本时
 * force_update 为 true，避免跳过强制版本。表中没有版本时返回 null。
 */
@Service
public class AppVersionService {

    private final AppVersionMapper appVersionMapper;
    private final String downloadPrefix;

    public AppVersionService(AppVersionMapper appVersionMapper, PestaiProperties properties) {
        this.appVersionMapper = appVersionMapper;
        String configured = properties.getDownloadBaseUrl();
        String prefix = configured == null || configured.isBlank()
                ? trim(properties.getPublicBaseUrl()) + "/download/"
                : configured.trim();
        this.downloadPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
    }

    @Transactional(readOnly = true)
    public AppVersionVO getLatest(Integer clientVersionCode) {
        AppVersion latest = appVersionMapper.selectLatest();
        if (latest == null) {
            return null;
        }
        boolean force = Boolean.TRUE.equals(latest.getForceUpdate());
        if (!force && clientVersionCode != null && clientVersionCode < latest.getVersionCode()) {
            force = appVersionMapper.countForceBetween(clientVersionCode, latest.getVersionCode()) > 0;
        }
        return new AppVersionVO(latest.getVersionCode(), latest.getVersionName(),
                toDownloadUrl(latest.getDownloadUrl()), force, latest.getReleaseNote());
    }

    String toDownloadUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return downloadPrefix + (value.startsWith("/") ? value.substring(1) : value);
    }

    private static String trim(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
