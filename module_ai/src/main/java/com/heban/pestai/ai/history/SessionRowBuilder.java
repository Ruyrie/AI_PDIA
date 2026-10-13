package com.heban.pestai.ai.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.ai.util.AiFormat;
import com.heban.pestai.common.format.TimeFormatter;
import com.heban.pestai.common.vo.SessionItemVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 由已加载的会话（按 updated_at 倒序）生成 Q03 列表行（纯 Java，now 作为参数传入，便于单元测试）：
 * 按 updated_at 分为「今天」「昨天」「近 7 天」「更早」，遇到新分组先插入分组标题，组内标记首末行（分组圆角）；
 * 正在加载下一页 / 加载失败时末尾追加底部提示。
 */
public final class SessionRowBuilder {

    public static final int GROUP_TODAY = 0;
    public static final int GROUP_YESTERDAY = 1;
    public static final int GROUP_WEEK = 2;
    public static final int GROUP_EARLIER = 3;

    static final String[] GROUP_TITLES = {"今天", "昨天", "近 7 天", "更早"};
    /** 标题为空时（服务器一般会返回「新对话」） */
    static final String DEFAULT_TITLE = "新对话";

    private SessionRowBuilder() {
    }

    /**
     * 分组：与今天同一天（或比今天晚）→ 今天；前一天 → 昨天；2–6 天前 → 近 7 天；更早或时间为空 → 更早。
     */
    public static int groupOf(@Nullable Date time, @NonNull LocalDate today) {
        if (time == null) {
            return GROUP_EARLIER;
        }
        LocalDate day = TimeFormatter.toLocal(time).toLocalDate();
        if (!day.isBefore(today)) {
            return GROUP_TODAY;
        }
        if (day.equals(today.minusDays(1))) {
            return GROUP_YESTERDAY;
        }
        if (!day.isBefore(today.minusDays(6))) {
            return GROUP_WEEK;
        }
        return GROUP_EARLIER;
    }

    @NonNull
    public static String groupTitle(int group) {
        return GROUP_TITLES[Math.max(0, Math.min(GROUP_TITLES.length - 1, group))];
    }

    /**
     * @param sessions    已加载的会话
     * @param now         设备当前时间
     * @param footerState 0（不显示）/ {@link SessionRow#FOOTER_LOADING} / {@link SessionRow#FOOTER_FAILED}
     * @return sessions 为空时返回空列表
     */
    @NonNull
    public static List<SessionRow> build(@NonNull List<SessionItemVO> sessions, @NonNull LocalDateTime now,
                                         int footerState) {
        int n = sessions.size();
        List<SessionRow> rows = new ArrayList<>(n + 5);
        if (n == 0) {
            return rows;
        }
        LocalDate today = now.toLocalDate();
        int[] groups = new int[n];
        for (int i = 0; i < n; i++) {
            groups[i] = groupOf(sessions.get(i).getUpdatedAt(), today);
        }
        Set<String> headerKeys = new HashSet<>();
        for (int i = 0; i < n; i++) {
            boolean first = i == 0 || groups[i] != groups[i - 1];
            boolean last = i == n - 1 || groups[i] != groups[i + 1];
            if (first) {
                String key = "h_" + groups[i];
                if (!headerKeys.add(key)) {
                    // 服务器顺序异常导致同一分组出现两段时，保证 key 唯一
                    key = key + "_" + i;
                    headerKeys.add(key);
                }
                rows.add(SessionRow.header(key, groupTitle(groups[i]), i == 0));
            }
            SessionItemVO vo = sessions.get(i);
            String title = AiFormat.isBlank(vo.getTitle()) ? DEFAULT_TITLE : vo.getTitle().trim();
            String preview = vo.getLastMessage() == null ? "" : singleLine(vo.getLastMessage());
            rows.add(SessionRow.session(vo.getSessionId(), title, preview,
                    AiFormat.sessionTime(vo.getUpdatedAt(), now), vo.getRecordId() != null && vo.getRecordId() > 0,
                    first, last));
        }
        if (footerState != 0) {
            rows.add(SessionRow.footer(footerState));
        }
        return rows;
    }

    /** 预览只显示一行：换行替换为空格 */
    @NonNull
    static String singleLine(@NonNull String text) {
        return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }
}
