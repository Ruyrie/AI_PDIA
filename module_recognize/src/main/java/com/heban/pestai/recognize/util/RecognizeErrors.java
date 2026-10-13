package com.heban.pestai.recognize.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.recognize.R;

/**
 * 识别失败的错误映射（S02 的 RecognizeService、R01 / R02 的重新识别共用）。
 *
 * <table>
 *     <tr><th>失败来源</th><th>error_type</th><th>fail_reason</th></tr>
 *     <tr><td>42201；本机 ImageReadException</td><td>IMAGE_BAD</td><td>IMAGE_BAD</td></tr>
 *     <tr><td>50401；读取超时</td><td>FAILED</td><td>TIMEOUT</td></tr>
 *     <tr><td>50201、50001、40001</td><td>FAILED</td><td>UNAVAILABLE</td></tr>
 *     <tr><td>断网、连接失败等其他异常；识别被系统中断</td><td>FAILED</td><td>NETWORK</td></tr>
 * </table>
 */
public final class RecognizeErrors {

    private RecognizeErrors() {
    }

    /** 错误码：ApiException 取业务码；网络异常 -1、超时 -2；本机照片无法读取按 -1 */
    public static int codeOf(@Nullable Throwable t) {
        if (t instanceof ImageCompressor.ImageReadException) {
            return ApiException.NETWORK;
        }
        return ApiException.codeOf(t);
    }

    /** 失败原因码 TIMEOUT / UNAVAILABLE / IMAGE_BAD / NETWORK */
    @NonNull
    public static String reasonOf(@Nullable Throwable t) {
        if (t instanceof ImageCompressor.ImageReadException) {
            return FailReason.IMAGE_BAD;
        }
        return reasonOfCode(ApiException.codeOf(t));
    }

    @NonNull
    public static String reasonOfCode(int code) {
        switch (code) {
            case ApiException.IMAGE_BAD:
                return FailReason.IMAGE_BAD;
            case ApiException.AI_TIMEOUT:
            case ApiException.TIMEOUT:
                return FailReason.TIMEOUT;
            case ApiException.AI_UNAVAILABLE:
            case ApiException.SERVER_ERROR:
            case ApiException.PARAM_ERROR:
                return FailReason.UNAVAILABLE;
            default:
                return FailReason.NETWORK;
        }
    }

    /** 失败原因码 → R02 的异常类型：IMAGE_BAD 为照片不清晰，其余为识别失败 */
    @NonNull
    public static String typeOfReason(@Nullable String failReason) {
        return FailReason.IMAGE_BAD.equals(failReason) ? ErrorType.IMAGE_BAD : ErrorType.FAILED;
    }

    @NonNull
    public static String typeOf(@Nullable Throwable t) {
        return typeOfReason(reasonOf(t));
    }

    /** 记录状态（SUCCESS / IMAGE_BAD / FAILED）→ R02 的异常类型 */
    @NonNull
    public static String typeOfStatus(@Nullable String status) {
        return ErrorType.IMAGE_BAD.equals(status) ? ErrorType.IMAGE_BAD : ErrorType.FAILED;
    }

    /**
     * 识别失败的提示文案（字符串资源）；返回 0 表示使用服务器返回的文案（如 40001「原照片已不存在，请重新拍摄」）。
     */
    @StringRes
    public static int messageRes(@Nullable Throwable t) {
        if (t instanceof ImageCompressor.ImageReadException) {
            return R.string.scan_err_image_unreadable;
        }
        switch (ApiException.codeOf(t)) {
            case ApiException.IMAGE_BAD:
                return R.string.scan_err_image_bad;
            case ApiException.AI_UNAVAILABLE:
                return R.string.scan_err_unavailable;
            case ApiException.AI_TIMEOUT:
            case ApiException.TIMEOUT:
                return R.string.scan_err_timeout;
            case ApiException.SERVER_ERROR:
                return R.string.scan_err_server;
            case ApiException.NETWORK:
                return R.string.scan_err_network;
            default:
                return 0;
        }
    }

    /** 是否登录过期（AuthInterceptor 已统一清除登录态并跳转登录页，页面不再另行处理） */
    public static boolean isUnauthorized(int code) {
        return code == ApiException.UNAUTHORIZED;
    }
}
