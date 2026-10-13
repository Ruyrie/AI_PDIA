package com.heban.pestai.mine.format;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.format.TimeFormatter;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.UserStatsVO;

import java.time.LocalDateTime;

/**
 * 我的页统计文字（模块09 技术准备文档 2.8「从 VO 派生界面文字」）。stats 为空（游客或尚未加载）时显示「--」。
 * now 作为参数传入，便于单元测试。
 */
public final class StatsFormatter {

    public static final String PLACEHOLDER = "--";

    private StatsFormatter() {
    }

    @NonNull
    public static String total(@Nullable UserStatsVO stats) {
        return stats == null ? PLACEHOLDER : String.valueOf(stats.getTotal());
    }

    @NonNull
    public static String pestKinds(@Nullable UserStatsVO stats) {
        return stats == null ? PLACEHOLDER : String.valueOf(stats.getPestKinds());
    }

    @NonNull
    public static String aiCount(@Nullable UserStatsVO stats) {
        return stats == null ? PLACEHOLDER : String.valueOf(stats.getAiCount());
    }

    /** 列表「识别记录」右侧的「36 条」；未加载时返回空串（不显示） */
    @NonNull
    public static String recordCount(@Nullable UserStatsVO stats) {
        return stats == null ? "" : stats.getTotal() + " 条";
    }

    /** 本月已识别 9 次 */
    @NonNull
    public static String month(@Nullable UserStatsVO stats) {
        return "本月已识别 " + (stats == null ? PLACEHOLDER : String.valueOf(stats.getMonthCount())) + " 次";
    }

    /**
     * 最近一次：黄瓜霜霉病 · 今天 14:20。
     * top_name 为空时按状态显示「照片不清晰」（IMAGE_BAD）或「识别失败」；last_record 为空显示「最近一次：暂无」。
     */
    @NonNull
    public static String lastRecord(@Nullable UserStatsVO stats, @NonNull LocalDateTime now) {
        if (stats == null) {
            return "最近一次：" + PLACEHOLDER;
        }
        RecordItemVO record = stats.getLastRecord();
        if (record == null) {
            return "最近一次：暂无";
        }
        String name = record.getTopName();
        if (name == null || name.trim().isEmpty()) {
            name = RecognitionStatus.IMAGE_BAD.equals(record.getStatus()) ? "照片不清晰" : "识别失败";
        }
        String time = TimeFormatter.formatRecent(record.getCreatedAt(), now);
        return time.isEmpty() ? "最近一次：" + name : "最近一次：" + name + " · " + time;
    }
}
