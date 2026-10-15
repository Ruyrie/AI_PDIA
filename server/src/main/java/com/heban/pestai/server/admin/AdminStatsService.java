package com.heban.pestai.server.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.admin.entity.DailyCount;
import com.heban.pestai.server.admin.entity.NameCount;
import com.heban.pestai.server.admin.entity.RangeCount;
import com.heban.pestai.server.admin.mapper.AdminStatsMapper;
import com.heban.pestai.server.admin.vo.NameValueVO;
import com.heban.pestai.server.admin.vo.StatsDistributionVO;
import com.heban.pestai.server.admin.vo.StatsOverviewVO;
import com.heban.pestai.server.admin.vo.StatsTrendVO;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.service.UserProfile;

/**
 * 后台数据统计（docs/后台管理接口.md「二、数据统计」）。日期按 Asia/Shanghai，「近 7 天」为含今天在内的 7 个自然日。
 * <ul>
 *   <li>活跃用户 = 当天有识别、有 AI 提问，或 last_login_at 落在当天的用户（去重）。t_user 只保存最近一次登录时间，
 *       因此「当天登录」只能统计到之后没有再登录的那一次（近似值）；令牌有效期 30 天，一直保持登录的用户只按识别 / 提问计入；</li>
 *   <li>识别次数含用户已删除（逻辑删除）的记录；后台彻底删除的记录、注销账号的数据不再计入；</li>
 *   <li>AI 提问 = role 为 user 的消息（重新生成不新增提问）；删除对话后其中的提问不再计入；</li>
 *   <li>短信 = t_sms_code 的行数：每发送一条验证码写一行，发送失败的已回滚不计入；注销账号会删除该手机号的记录；</li>
 *   <li>备用模型完成数（迁移 007 的 fallback = 1）：AI 回答按回答的 created_at，识别（成功与照片不清晰）按记录的 created_at
 *       （重新识别不改变 created_at）；迁移前的旧数据都按 0 计。</li>
 * </ul>
 */
@Service
public class AdminStatsService {

    static final int TOP_LIMIT = 10;
    private static final String MODE_PEST_LABEL = "病虫害识别";
    private static final String MODE_CROP_LABEL = "作物与杂草识别";
    /** 后台清除了种植身份的用户（identity 为 NULL） */
    static final String IDENTITY_UNSET_LABEL = "未设置";

    private final AdminStatsMapper mapper;
    private final Clock clock;

    public AdminStatsService(AdminStatsMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 各计数在同一只读事务（一致性快照）中完成 */
    @Transactional(readOnly = true)
    public StatsOverviewVO overview() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrow = today.plusDays(1).atStartOfDay();
        LocalDateTime weekStart = today.minusDays(6).atStartOfDay();

        StatsOverviewVO.Users users = new StatsOverviewVO.Users(
                mapper.countUsers(),
                mapper.countUsersCreatedBetween(todayStart, tomorrow),
                mapper.countActiveUsersBetween(weekStart, tomorrow),
                mapper.countDisabledUsers());

        RangeCount recToday = orEmpty(mapper.recognitionsBetween(todayStart, tomorrow));
        RangeCount recWeek = orEmpty(mapper.recognitionsBetween(weekStart, tomorrow));
        StatsOverviewVO.Recognitions recognitions = new StatsOverviewVO.Recognitions(
                mapper.countRecognitions(), recToday.totalOrZero(), ratio(recWeek.partOrZero(), recWeek.totalOrZero()));

        RangeCount askToday = orEmpty(mapper.questionsBetween(todayStart, tomorrow));
        RangeCount askWeek = orEmpty(mapper.questionsBetween(weekStart, tomorrow));
        StatsOverviewVO.Ai ai = new StatsOverviewVO.Ai(
                mapper.countQuestions(), askToday.totalOrZero(), ratio(askWeek.partOrZero(), askWeek.totalOrZero()));

        StatsOverviewVO.Sms sms = new StatsOverviewVO.Sms(
                mapper.countSmsBetween(todayStart, tomorrow), mapper.countSmsBetween(weekStart, tomorrow));
        StatsOverviewVO.Fallback fallback = new StatsOverviewVO.Fallback(
                mapper.countFallbackAnswersBetween(weekStart, tomorrow),
                mapper.countFallbackRecognitionsBetween(weekStart, tomorrow));
        return new StatsOverviewVO(users, recognitions, ai, sms, fallback);
    }

    /** days 只能是 7 或 30；各数组与 dates 等长，没有数据的日期补 0 */
    @Transactional(readOnly = true)
    public StatsTrendVO trend(int days) {
        if (days != 7 && days != 30) {
            throw BizException.param("days 只能是 7 或 30");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate first = today.minusDays(days - 1L);
        LocalDateTime from = first.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();

        List<LocalDate> dates = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            dates.add(first.plusDays(i));
        }
        List<DailyCount> recognitions = mapper.dailyRecognitions(from, to);
        return new StatsTrendVO(
                dates.stream().map(LocalDate::toString).toList(),
                fill(dates, mapper.dailyNewUsers(from, to), DailyCount::getTotal),
                fill(dates, mapper.dailyActiveUsers(from, to), DailyCount::getTotal),
                fill(dates, recognitions, DailyCount::getTotal),
                fill(dates, recognitions, DailyCount::getSuccess),
                fill(dates, mapper.dailyQuestions(from, to), DailyCount::getTotal),
                fill(dates, mapper.dailySms(from, to), DailyCount::getTotal),
                fill(dates, mapper.dailyFallbackUsed(from, to), DailyCount::getTotal));
    }

    /**
     * 识别模式（名称为「病虫害识别」「作物与杂草识别」）、识别成功的结果前 10、种植身份、用户作物前 10
     * （作物按逗号 / 中文逗号 / 顿号拆分并按 UserProfile 的规则清洗后计数，同一用户的同一作物只算一次）。
     */
    @Transactional(readOnly = true)
    public StatsDistributionVO distribution() {
        List<NameValueVO> modes = toValues(mapper.countByMode(), AdminStatsService::modeLabel);
        List<NameValueVO> topResults = toValues(mapper.topResults(TOP_LIMIT), Function.identity());
        List<NameValueVO> identities = toValues(mapper.countByIdentity(),
                name -> name == null || name.isBlank() ? IDENTITY_UNSET_LABEL : name);
        return new StatsDistributionVO(modes, topResults, identities, topCrops(mapper.selectAllCrops()));
    }

    static String modeLabel(String mode) {
        if (Mode.PEST.equals(mode)) {
            return MODE_PEST_LABEL;
        }
        if (Mode.CROP.equals(mode)) {
            return MODE_CROP_LABEL;
        }
        return mode == null ? "未知" : mode;
    }

    static List<NameValueVO> topCrops(List<String> cropsOfUsers) {
        if (cropsOfUsers == null || cropsOfUsers.isEmpty()) {
            return List.of();
        }
        Map<String, Long> counts = new HashMap<>();
        for (String crops : cropsOfUsers) {
            for (String crop : UserProfile.cropsOf(crops)) {
                counts.merge(crop, 1L, Long::sum);
            }
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue(Collections.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        return entries.stream().limit(TOP_LIMIT).map(e -> new NameValueVO(e.getKey(), e.getValue())).toList();
    }

    private static List<NameValueVO> toValues(List<NameCount> rows, Function<String, String> label) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(r -> new NameValueVO(label.apply(r.getName()), r.getTotal() == null ? 0 : r.getTotal()))
                .toList();
    }

    static List<Long> fill(List<LocalDate> dates, List<DailyCount> rows, Function<DailyCount, Long> value) {
        Map<LocalDate, Long> byDate = new LinkedHashMap<>();
        if (rows != null) {
            for (DailyCount row : rows) {
                if (row.getStatDate() != null) {
                    Long v = value.apply(row);
                    byDate.merge(row.getStatDate(), v == null ? 0L : v, Long::sum);
                }
            }
        }
        return dates.stream().map(d -> byDate.getOrDefault(d, 0L)).toList();
    }

    /** 0–1 的小数，保留 4 位；分母为 0 时为 0 */
    static double ratio(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round(part * 10000.0 / total) / 10000.0;
    }

    private static RangeCount orEmpty(RangeCount count) {
        return count == null ? new RangeCount() : count;
    }
}
