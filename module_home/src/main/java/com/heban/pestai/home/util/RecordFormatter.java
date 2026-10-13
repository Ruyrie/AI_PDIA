package com.heban.pestai.home.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.constant.Severity;
import com.heban.pestai.common.format.RecordTag;
import com.heban.pestai.common.format.TimeFormatter;
import com.heban.pestai.common.vo.RecordItemVO;

import java.time.LocalDateTime;

/**
 * 由 RecordItemVO 生成最近识别行的名称、副标题与标签（模块04 技术准备文档 2.8 第 1 点）。
 * 纯 Java 静态方法，当前时间由参数传入；ViewModel 只保存 VO 原值，显示文字在绑定时生成。
 */
public final class RecordFormatter {

    /** 副标题各段之间的分隔 */
    public static final String SEPARATOR = " · ";
    public static final String HEALTHY_TEXT = "未发现病虫害";
    public static final String MATCH_PREFIX = "匹配度 ";

    private RecordFormatter() {
    }

    /**
     * 归一化识别状态：SUCCESS / IMAGE_BAD 原样返回，FAILED 与未知取值都按 FAILED 处理。
     */
    @NonNull
    public static String status(@Nullable RecordItemVO item) {
        String status = item == null ? null : item.getStatus();
        if (RecognitionStatus.SUCCESS.equals(status) || RecognitionStatus.IMAGE_BAD.equals(status)) {
            return status;
        }
        return RecognitionStatus.FAILED;
    }

    public static boolean isSuccess(@Nullable RecordItemVO item) {
        return RecognitionStatus.SUCCESS.equals(status(item));
    }

    /** R02 的 error_type：IMAGE_BAD 记录为 IMAGE_BAD，其余（FAILED、未知取值）为 FAILED */
    @NonNull
    public static String errorType(@Nullable RecordItemVO item) {
        return RecognitionStatus.IMAGE_BAD.equals(status(item)) ? ErrorType.IMAGE_BAD : ErrorType.FAILED;
    }

    /** PEST →「病虫害」，CROP →「作物 / 杂草」，其他返回 null */
    @Nullable
    public static String modeText(@Nullable String mode) {
        return Mode.text(mode);
    }

    /** 0.92 →「92%」（乘 100 后四舍五入取整）；为 null 时返回 null */
    @Nullable
    public static String percentText(@Nullable Double confidence) {
        if (confidence == null || confidence.isNaN()) {
            return null;
        }
        return Math.round(confidence * 100) + "%";
    }

    /** TIMEOUT「网络超时」、UNAVAILABLE「服务暂不可用」、NETWORK「网络异常」、IMAGE_BAD「画面模糊」；空或未知返回 null */
    @Nullable
    public static String failReasonText(@Nullable String failReason) {
        return FailReason.text(failReason);
    }

    /** SUCCESS 返回 top_name，FAILED（含未知状态）返回「识别失败」，IMAGE_BAD 返回「照片不清晰」 */
    @NonNull
    public static String title(@Nullable RecordItemVO item) {
        String status = status(item);
        if (RecognitionStatus.SUCCESS.equals(status)) {
            String name = item == null ? null : item.getTopName();
            return name == null || name.isEmpty() ? RecognitionStatus.text(RecognitionStatus.SUCCESS) : name;
        }
        //noinspection ConstantConditions 两种失败状态均有文字
        return RecognitionStatus.text(status);
    }

    /**
     * 副标题：「模式 · 中间段 · 时间」，为 null 或空的段整段省略。
     * 中间段：SUCCESS 且健康为「未发现病虫害」，否则「匹配度 92%」；IMAGE_BAD 为「画面模糊」；
     * FAILED 为失败原因文字。
     */
    @NonNull
    public static String meta(@Nullable RecordItemVO item, @NonNull LocalDateTime now) {
        if (item == null) {
            return "";
        }
        String middle;
        String status = status(item);
        if (RecognitionStatus.SUCCESS.equals(status)) {
            if (Severity.HEALTHY.equals(item.getSeverity())) {
                middle = HEALTHY_TEXT;
            } else {
                String percent = percentText(item.getConfidence());
                middle = percent == null ? null : MATCH_PREFIX + percent;
            }
        } else if (RecognitionStatus.IMAGE_BAD.equals(status)) {
            middle = failReasonText(FailReason.IMAGE_BAD);
        } else {
            middle = failReasonText(item.getFailReason());
        }
        return join(modeText(item.getMode()), middle, TimeFormatter.formatRecent(item.getCreatedAt(), now));
    }

    /** 标签：未知状态按 FAILED 处理；返回 null 时隐藏标签 */
    @Nullable
    public static RecordTag tag(@Nullable RecordItemVO item) {
        if (item == null) {
            return null;
        }
        return RecordTag.of(item.getMode(), status(item), item.getSeverity());
    }

    @NonNull
    private static String join(@Nullable String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
