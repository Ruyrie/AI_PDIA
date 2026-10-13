package com.heban.pestai.recognize.model;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.recognize.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * R02 识别异常页两种状态的配置表。布局不写判断，ScanErrorViewModel 按配置写入 LiveData。
 *
 * <table>
 *     <tr><th></th><th>IMAGE_BAD（照片不清晰）</th><th>FAILED（识别失败）</th></tr>
 *     <tr><td>图标 / 底色</td><td>blur_on / warningContainer</td><td>cloud_off / errorContainer</td></tr>
 *     <tr><td>说明</td><td>固定</td><td>按 fail_reason：TIMEOUT / UNAVAILABLE / NETWORK</td></tr>
 *     <tr><td>建议</td><td>拍摄建议 3 条</td><td>可能的原因 3 条（无记录时 2 条）</td></tr>
 *     <tr><td>左侧描边按钮</td><td>重新识别 → RETRY</td><td>重拍 → RETAKE</td></tr>
 *     <tr><td>右侧填充按钮</td><td>重拍 → RETAKE</td><td>重试 → RETRY</td></tr>
 * </table>
 */
public final class ErrorConfig {

    public enum Action {
        /** 重新识别（有记录调用 retry 接口，无记录进入 S02 重新上传） */
        RETRY,
        /** 回到相机重拍 */
        RETAKE
    }

    /** IMAGE_BAD / FAILED */
    @NonNull
    public final String errorType;
    /** IMAGE_BAD / TIMEOUT / UNAVAILABLE / NETWORK */
    @NonNull
    public final String failReason;
    @DrawableRes
    public final int iconRes;
    @AttrRes
    public final int iconBgAttr;
    @AttrRes
    public final int iconFgAttr;
    @StringRes
    public final int titleRes;
    @StringRes
    public final int messageRes;
    @StringRes
    public final int tipsTitleRes;
    @NonNull
    public final List<Integer> tipRes;
    /** 左侧描边按钮 */
    @StringRes
    public final int secondaryTextRes;
    @DrawableRes
    public final int secondaryIconRes;
    @NonNull
    public final Action secondaryAction;
    /** 右侧填充按钮 */
    @StringRes
    public final int primaryTextRes;
    @DrawableRes
    public final int primaryIconRes;
    @NonNull
    public final Action primaryAction;

    private ErrorConfig(@NonNull String errorType, @NonNull String failReason, int iconRes, int iconBgAttr,
                        int iconFgAttr, int titleRes, int messageRes, int tipsTitleRes, @NonNull List<Integer> tipRes,
                        int secondaryTextRes, int secondaryIconRes, @NonNull Action secondaryAction,
                        int primaryTextRes, int primaryIconRes, @NonNull Action primaryAction) {
        this.errorType = errorType;
        this.failReason = failReason;
        this.iconRes = iconRes;
        this.iconBgAttr = iconBgAttr;
        this.iconFgAttr = iconFgAttr;
        this.titleRes = titleRes;
        this.messageRes = messageRes;
        this.tipsTitleRes = tipsTitleRes;
        this.tipRes = Collections.unmodifiableList(tipRes);
        this.secondaryTextRes = secondaryTextRes;
        this.secondaryIconRes = secondaryIconRes;
        this.secondaryAction = secondaryAction;
        this.primaryTextRes = primaryTextRes;
        this.primaryIconRes = primaryIconRes;
        this.primaryAction = primaryAction;
    }

    /**
     * @param errorType  Intent 参数 error_type，无法识别时按 FAILED
     * @param failReason Intent 参数 fail_reason，FAILED 状态下为空或无法识别时按 UNAVAILABLE
     * @param hasRecord  record_id 是否大于 0；没有记录时识别失败只显示前 2 条原因
     */
    @NonNull
    public static ErrorConfig of(@Nullable String errorType, @Nullable String failReason, boolean hasRecord) {
        String type = normalizeType(errorType);
        if (ErrorType.IMAGE_BAD.equals(type)) {
            return new ErrorConfig(ErrorType.IMAGE_BAD, FailReason.IMAGE_BAD,
                    com.heban.pestai.base.R.drawable.ic_blur_on,
                    com.heban.pestai.base.R.attr.colorWarningContainer,
                    com.heban.pestai.base.R.attr.colorOnWarningContainer,
                    R.string.scan_error_blur_title,
                    R.string.scan_error_blur_message,
                    R.string.scan_error_blur_tips_title,
                    Arrays.asList(R.string.scan_error_blur_tip_1, R.string.scan_error_blur_tip_2,
                            R.string.scan_error_blur_tip_3),
                    R.string.scan_error_action_recognize_again, com.heban.pestai.base.R.drawable.ic_refresh,
                    Action.RETRY,
                    R.string.scan_error_action_retake, com.heban.pestai.base.R.drawable.ic_photo_camera,
                    Action.RETAKE);
        }
        String reason = normalizeReason(failReason);
        int message;
        switch (reason) {
            case FailReason.TIMEOUT:
                message = R.string.scan_error_failed_message_timeout;
                break;
            case FailReason.NETWORK:
                message = R.string.scan_error_failed_message_network;
                break;
            default:
                message = R.string.scan_error_failed_message_unavailable;
                break;
        }
        List<Integer> tips = new ArrayList<>(Arrays.asList(R.string.scan_error_failed_tip_1,
                R.string.scan_error_failed_tip_2));
        if (hasRecord) {
            tips.add(R.string.scan_error_failed_tip_3);
        }
        return new ErrorConfig(ErrorType.FAILED, reason,
                com.heban.pestai.base.R.drawable.ic_cloud_off,
                com.google.android.material.R.attr.colorErrorContainer,
                com.google.android.material.R.attr.colorOnErrorContainer,
                R.string.scan_error_failed_title,
                message,
                R.string.scan_error_failed_tips_title,
                tips,
                R.string.scan_error_action_retake, com.heban.pestai.base.R.drawable.ic_photo_camera,
                Action.RETAKE,
                R.string.scan_error_action_retry, com.heban.pestai.base.R.drawable.ic_refresh,
                Action.RETRY);
    }

    /** error_type 规范化：只认 IMAGE_BAD，其余按 FAILED */
    @NonNull
    public static String normalizeType(@Nullable String errorType) {
        return ErrorType.IMAGE_BAD.equals(errorType) ? ErrorType.IMAGE_BAD : ErrorType.FAILED;
    }

    /** FAILED 状态的 fail_reason 规范化：TIMEOUT / NETWORK / UNAVAILABLE（缺省） */
    @NonNull
    public static String normalizeReason(@Nullable String failReason) {
        if (FailReason.TIMEOUT.equals(failReason) || FailReason.NETWORK.equals(failReason)) {
            return failReason;
        }
        return FailReason.UNAVAILABLE;
    }
}
