package com.heban.pestai.record.ui;

import androidx.annotation.NonNull;

import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.record.util.DateGroupFormatter;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 由已加载的记录（按 created_at 倒序、已去重）生成列表行（模块07 技术准备文档 2.7.3「publishRows()」）：
 * 遇到新日期先插入日期标题，再插入记录行并标记组内首末行，最后追加底部提示。纯 Java，可单元测试。
 */
public final class HistoryRowBuilder {

    static final String UNKNOWN_DAY = "未知日期";

    private HistoryRowBuilder() {
    }

    /**
     * @param records     已加载的记录
     * @param today       设备当天日期
     * @param zone        created_at 换算本地日期 / 时间使用的时区
     * @param footerState 底部提示状态（HistoryRow.FOOTER_*）
     * @param total       当前筛选下的记录总数
     * @return records 为空时返回空列表（不显示底部提示）
     */
    @NonNull
    public static List<HistoryRow> build(@NonNull List<RecordItemVO> records, @NonNull LocalDate today,
                                         @NonNull ZoneId zone, int footerState, int total) {
        int n = records.size();
        List<HistoryRow> rows = new ArrayList<>(n + Math.min(n, 16) + 1);
        if (n == 0) {
            return rows;
        }
        LocalDate[] days = new LocalDate[n];
        for (int i = 0; i < n; i++) {
            days[i] = DateGroupFormatter.toLocalDate(records.get(i).getCreatedAt(), zone);
        }
        Set<String> headerKeys = new HashSet<>();
        for (int i = 0; i < n; i++) {
            boolean first = i == 0 || !Objects.equals(days[i], days[i - 1]);
            boolean last = i == n - 1 || !Objects.equals(days[i], days[i + 1]);
            if (first) {
                LocalDate day = days[i];
                String key = "h_" + (day == null ? "unknown" : day.toString());
                if (!headerKeys.add(key)) {
                    // 服务器顺序异常导致同一天出现两段时，保证 key 唯一，DiffUtil 才能正确比较
                    key = key + "_" + i;
                    headerKeys.add(key);
                }
                String title = day == null ? UNKNOWN_DAY : DateGroupFormatter.format(day, today);
                rows.add(HistoryRow.header(key, title, i == 0));
            }
            rows.add(HistoryRow.record(records.get(i), first, last, zone));
        }
        rows.add(HistoryRow.footer(footerState, Math.max(total, n)));
        return rows;
    }
}
