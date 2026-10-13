package com.heban.pestai.mine.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.AppVersionVO;

/**
 * 预留的更新类型判断（模块09 技术准备文档 2.8 第 4 节，课程「不同的软件更新策略」）。纯 Java，v1.0 不在界面中使用。
 * <ol>
 *     <li>AppVersionVO 为空，或其 version_code 不大于当前版本号 → NONE</li>
 *     <li>force_update 为 true → FORCE（不受忽略记录影响）</li>
 *     <li>version_code 等于已忽略的版本号 → NONE（启动检查时用户已选「以后再说」）</li>
 *     <li>其他 → OPTIONAL</li>
 * </ol>
 */
public enum UpdateType {
    /** 已是最新 */
    NONE,
    /** 可选更新：可取消的对话框 */
    OPTIONAL,
    /** 强制更新：不可取消，只有「立即更新」 */
    FORCE;

    /** 手动检查时传入，表示不使用忽略记录 */
    public static final long NO_IGNORED = -1;

    @NonNull
    public static UpdateType decide(long currentCode, @Nullable AppVersionVO latest, long ignoredCode) {
        if (latest == null || latest.getVersionCode() <= currentCode) {
            return NONE;
        }
        if (latest.isForceUpdate()) {
            return FORCE;
        }
        if (ignoredCode != NO_IGNORED && latest.getVersionCode() == ignoredCode) {
            return NONE;
        }
        return OPTIONAL;
    }
}
