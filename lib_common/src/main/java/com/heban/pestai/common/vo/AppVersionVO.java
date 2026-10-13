package com.heban.pestai.common.vo;

import java.util.Objects;

/**
 * 最新版本信息（app/version，预留，v1.0 不调用）。
 * 前后端共用的 VO，字段与《05-公共数据模型与示例数据》一致；JSON 使用下划线命名（Gson LOWER_CASE_WITH_UNDERSCORES）。
 */
public class AppVersionVO {

    /** 版本号，与 BuildConfig.VERSION_CODE 比较 */
    private int versionCode;
    /** 版本名 */
    private String versionName;
    /** 安装包地址 */
    private String downloadUrl;
    /** 是否强制更新 */
    private boolean forceUpdate;
    /** 更新说明，可空 */
    private String releaseNote;

    public AppVersionVO() {
    }

    public int getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(int versionCode) {
        this.versionCode = versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public boolean isForceUpdate() {
        return forceUpdate;
    }

    public void setForceUpdate(boolean forceUpdate) {
        this.forceUpdate = forceUpdate;
    }

    public String getReleaseNote() {
        return releaseNote;
    }

    public void setReleaseNote(String releaseNote) {
        this.releaseNote = releaseNote;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AppVersionVO that = (AppVersionVO) o;
        return versionCode == that.versionCode
                && Objects.equals(versionName, that.versionName)
                && Objects.equals(downloadUrl, that.downloadUrl)
                && forceUpdate == that.forceUpdate
                && Objects.equals(releaseNote, that.releaseNote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionCode, versionName, downloadUrl, forceUpdate, releaseNote);
    }
}
