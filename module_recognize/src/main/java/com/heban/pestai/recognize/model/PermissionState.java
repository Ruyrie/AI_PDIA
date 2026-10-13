package com.heban.pestai.recognize.model;

/**
 * S01 相机权限状态：未检查 / 已授权 / 拒绝（还能再次申请）/ 永久拒绝（只能去系统设置开启）。
 */
public enum PermissionState {
    UNKNOWN,
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED;

    public boolean isDenied() {
        return this == DENIED || this == PERMANENTLY_DENIED;
    }
}
