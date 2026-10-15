package com.heban.pestai.server.admin.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.admin.entity.DailyCount;
import com.heban.pestai.server.admin.entity.NameCount;
import com.heban.pestai.server.admin.entity.RangeCount;

/**
 * 后台数据统计（SQL 见 resources/mapper/AdminStatsMapper.xml）。时间范围一律为 [from, to)，按 created_at 等时间列过滤，
 * 走迁移 006 新增的时间索引。识别记录包含用户已删除（deleted = 1）的；AI 提问为 t_ai_message 中 role = user 的消息。
 */
@Mapper
public interface AdminStatsMapper {

    long countUsers();

    long countUsersCreatedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    long countDisabledUsers();

    /** 时间范围内的活跃用户数（去重）：有识别、有 AI 提问，或 last_login_at 落在范围内 */
    long countActiveUsersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    long countRecognitions();

    /** total 识别次数，part 其中成功（status = SUCCESS）的次数 */
    RangeCount recognitionsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    long countQuestions();

    /** total AI 提问数，part 其中带图提问数 */
    RangeCount questionsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 发送的短信验证码条数（每发送一条写一行 t_sms_code；发送失败的已回滚，不计入） */
    long countSmsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<DailyCount> dailyNewUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<DailyCount> dailyActiveUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** total 识别次数，success 成功次数 */
    List<DailyCount> dailyRecognitions(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<DailyCount> dailyQuestions(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<DailyCount> dailySms(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 由备用模型完成的 AI 回答数（role = assistant 且 fallback = 1，迁移 007） */
    long countFallbackAnswersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 由备用模型完成的识别数（fallback = 1，成功与照片不清晰；按 created_at，含用户已删除的记录） */
    long countFallbackRecognitionsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 每天由备用模型完成的 AI 回答数 + 识别数（total） */
    List<DailyCount> dailyFallbackUsed(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 识别模式分布（name 为 PEST / CROP） */
    List<NameCount> countByMode();

    /** 识别成功的 top_name 出现次数前 limit 名 */
    List<NameCount> topResults(@Param("limit") int limit);

    /** 种植身份分布 */
    List<NameCount> countByIdentity();

    /** 全部用户的 crops 原文（非空）；作物按逗号拆分后在 Java 中计数 */
    List<String> selectAllCrops();
}
