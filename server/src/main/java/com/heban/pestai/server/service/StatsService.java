package com.heban.pestai.server.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.entity.RecognitionStats;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.vo.UserStatsVO;

/**
 * 我的页统计（只统计 deleted = 0 的识别记录）：
 * total 全部记录数（含失败）；pest_kinds 为 PEST、SUCCESS 且 severity 不是「健康」的不同 top_name 个数；
 * month_count 本月（Asia/Shanghai）记录数；ai_count 该用户所有会话中 role 为 user 的消息数；last_record 最新一条。
 */
@Service
public class StatsService {

    private final RecognitionMapper recognitionMapper;
    private final AiMessageMapper aiMessageMapper;
    private final RecognitionAssembler assembler;
    private final Clock clock;

    public StatsService(RecognitionMapper recognitionMapper, AiMessageMapper aiMessageMapper,
                        RecognitionAssembler assembler, Clock clock) {
        this.recognitionMapper = recognitionMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.assembler = assembler;
        this.clock = clock;
    }

    /** 三次查询在同一只读事务（一致性快照）中完成，避免 total 与 last_record 对不上 */
    @Transactional(readOnly = true)
    public UserStatsVO getStats(long userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime nextMonthStart = monthStart.plusMonths(1);

        RecognitionStats counts = recognitionMapper.selectStatCounts(userId, monthStart, nextMonthStart);
        long aiCount = aiMessageMapper.countUserQuestions(userId);
        List<Recognition> latest = recognitionMapper.selectRecent(userId, 1);

        return new UserStatsVO(
                counts == null ? 0 : zero(counts.getTotal()),
                counts == null ? 0 : zero(counts.getPestKinds()),
                (int) aiCount,
                counts == null ? 0 : zero(counts.getMonthCount()),
                latest.isEmpty() ? null : assembler.toItem(latest.get(0)));
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }
}
