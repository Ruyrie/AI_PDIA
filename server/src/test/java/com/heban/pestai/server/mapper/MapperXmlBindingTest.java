package com.heban.pestai.server.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.heban.pestai.server.admin.entity.Admin;
import com.heban.pestai.server.admin.entity.AdminLog;
import com.heban.pestai.server.admin.entity.AdminUserRow;
import com.heban.pestai.server.admin.entity.DailyCount;
import com.heban.pestai.server.admin.entity.NameCount;
import com.heban.pestai.server.admin.entity.RangeCount;
import com.heban.pestai.server.admin.mapper.AdminLogMapper;
import com.heban.pestai.server.admin.mapper.AdminMapper;
import com.heban.pestai.server.admin.mapper.AdminStatsMapper;
import com.heban.pestai.server.admin.mapper.AdminUserMapper;
import com.heban.pestai.server.common.TicketPurpose;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiMessageImage;
import com.heban.pestai.server.entity.AiSessionSummary;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.entity.ResetTicket;
import com.heban.pestai.server.entity.SystemConfig;
import com.heban.pestai.server.entity.User;

/**
 * 不连接数据库，只解析 resources/mapper/*.xml：每个 Mapper 接口方法都必须有同名的 SQL 语句，
 * 避免语句 id 拼错到运行时才报「Invalid bound statement」。
 */
class MapperXmlBindingTest {

    private static Configuration parseAll() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml");
        assertThat(resources).isNotEmpty();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                new XMLMapperBuilder(in, configuration, resource.getFilename(), configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private static String sql(Configuration configuration, Class<?> mapper, String method, Object parameter) {
        return configuration.getMappedStatement(mapper.getName() + "." + method)
                .getBoundSql(parameter).getSql().replaceAll("\\s+", " ");
    }

    /** AI 问答思考过程与历史对话新增的语句：存在、参数绑定正确、关键条件与排序不丢 */
    @Test
    void aiReasoningAndHistoryStatementsAreBound() throws Exception {
        Configuration configuration = parseAll();

        AiMessage message = new AiMessage(9L, "assistant", "答", null, LocalDateTime.of(2026, 10, 13, 14, 23));
        message.setReasoningContent("想");
        message.setReasoningMs(1200);
        BoundSql insert = configuration.getMappedStatement(AiMessageMapper.class.getName() + ".insert").getBoundSql(message);
        assertThat(insert.getSql()).contains("reasoning_content").contains("reasoning_ms");
        assertThat(insert.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .contains("reasoningContent", "reasoningMs");

        Map<String, Object> bySession = Map.of("sessionId", 9L);
        assertThat(sql(configuration, AiMessageMapper.class, "selectBySession", bySession))
                .contains("reasoning_content, reasoning_ms").contains("ORDER BY id ASC");
        assertThat(sql(configuration, AiMessageMapper.class, "selectImageUrlsBySession", bySession))
                .contains("WHERE session_id = ?");
        assertThat(sql(configuration, AiMessageMapper.class, "deleteBySession", bySession))
                .contains("DELETE FROM t_ai_message WHERE session_id = ?");

        Map<String, Object> urls = new HashMap<>();
        urls.put("urls", List.of("chat/a.jpg", "chat/b.jpg"));
        BoundSql inUse = configuration.getMappedStatement(AiMessageMapper.class.getName() + ".selectImageUrlsInUse")
                .getBoundSql(urls);
        assertThat(inUse.getSql()).contains("image_url IN");
        // 两处（t_ai_message_image、t_ai_message）各绑定一次
        assertThat(inUse.getParameterMappings()).hasSize(4);

        Map<String, Object> page = new HashMap<>();
        page.put("userId", 10001L);
        page.put("offset", 20L);
        page.put("size", 20);
        String pageSql = sql(configuration, AiSessionMapper.class, "selectPageWithMessages", page);
        assertThat(pageSql).contains("EXISTS (SELECT 1 FROM t_ai_message e WHERE e.session_id = s.id)")
                .contains("ORDER BY s.updated_at DESC, s.id DESC")
                .contains("LIMIT ?, ?")
                .contains("AS last_message_id");
        assertThat(sql(configuration, AiSessionMapper.class, "countWithMessagesByUser", Map.of("userId", 10001L)))
                .contains("EXISTS");
        assertThat(sql(configuration, AiSessionMapper.class, "deleteByIdAndUser", Map.of("id", 1L, "userId", 2L)))
                .contains("WHERE id = ? AND user_id = ?");
        assertThat(configuration.getMappedStatement(AiSessionMapper.class.getName() + ".selectPageWithMessages")
                .getResultMaps().get(0).getType()).isEqualTo(AiSessionSummary.class);
    }

    /** 多图提问：图片行按下标写 sort_no；读取、删除、核对引用都覆盖 t_ai_message_image 与 t_ai_message.image_url 两处 */
    @Test
    void aiMessageImageStatementsAreBound() throws Exception {
        Configuration configuration = parseAll();
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 9, 30);

        Map<String, Object> insert = new HashMap<>();
        insert.put("messageId", 77L);
        insert.put("imageUrls", List.of("chat/20261014/a.jpg", "chat/20261014/b.png", "chat/20261014/c.jpg"));
        insert.put("createdAt", now);
        BoundSql images = configuration.getMappedStatement(AiMessageMapper.class.getName() + ".insertImages")
                .getBoundSql(insert);
        assertThat(images.getSql().replaceAll("\\s+", " "))
                .contains("INSERT INTO t_ai_message_image (message_id, sort_no, image_url, created_at) VALUES")
                .contains("(?, ?, ?, ?) , (?, ?, ?, ?) , (?, ?, ?, ?)");
        assertThat(images.getParameterMappings()).hasSize(12);
        // sort_no 绑定 foreach 的下标：0、1、2
        List<Object> sortNos = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            String property = images.getParameterMappings().get(row * 4 + 1).getProperty();
            sortNos.add(images.getAdditionalParameter(property));
        }
        assertThat(sortNos).containsExactly(0, 1, 2);
        assertThat(images.getAdditionalParameter(images.getParameterMappings().get(6).getProperty()))
                .isEqualTo("chat/20261014/b.png");

        Map<String, Object> ids = new HashMap<>();
        ids.put("messageIds", List.of(1L, 2L));
        BoundSql select = configuration.getMappedStatement(AiMessageMapper.class.getName() + ".selectImagesByMessageIds")
                .getBoundSql(ids);
        assertThat(select.getSql().replaceAll("\\s+", " "))
                .contains("FROM t_ai_message_image WHERE message_id IN ( ? , ? )")
                .contains("ORDER BY message_id ASC, sort_no ASC");
        assertThat(configuration.getMappedStatement(AiMessageMapper.class.getName() + ".selectImagesByMessageIds")
                .getResultMaps().get(0).getType()).isEqualTo(AiMessageImage.class);

        Map<String, Object> bySession = Map.of("sessionId", 9L);
        assertThat(sql(configuration, AiMessageMapper.class, "selectImageUrlsBySession", bySession))
                .contains("FROM t_ai_message_image i JOIN t_ai_message m ON i.message_id = m.id WHERE m.session_id = ?")
                .contains("UNION")
                .contains("FROM t_ai_message WHERE session_id = ? AND image_url IS NOT NULL");
        assertThat(sql(configuration, AiMessageMapper.class, "deleteImagesBySession", bySession))
                .contains("DELETE i FROM t_ai_message_image i JOIN t_ai_message m ON i.message_id = m.id WHERE m.session_id = ?");

        Map<String, Object> byUser = Map.of("userId", 10001L);
        assertThat(sql(configuration, AiMessageMapper.class, "selectImageUrlsByUser", byUser))
                .contains("FROM t_ai_message_image i").contains("UNION").contains("SELECT m.image_url FROM t_ai_message m");
        assertThat(sql(configuration, AiMessageMapper.class, "deleteImagesByUser", byUser))
                .contains("DELETE i FROM t_ai_message_image i")
                .contains("JOIN t_ai_session s ON m.session_id = s.id WHERE s.user_id = ?");

        Map<String, Object> urls = new HashMap<>();
        urls.put("urls", List.of("chat/a.jpg", "chat/b.jpg"));
        BoundSql inUse = configuration.getMappedStatement(AiMessageMapper.class.getName() + ".selectImageUrlsInUse")
                .getBoundSql(urls);
        assertThat(inUse.getSql().replaceAll("\\s+", " "))
                .contains("FROM t_ai_message_image WHERE image_url IN")
                .contains("UNION")
                .contains("FROM t_ai_message WHERE image_url IN");
        assertThat(inUse.getParameterMappings()).hasSize(4);
    }

    /** 找回账号与恢复码新增 / 修改的语句：动态 SQL 按参数取舍，purpose 读写都不丢 */
    @Test
    void recoverAndRecoveryCodeStatementsAreBound() throws Exception {
        Configuration configuration = parseAll();

        assertThat(sql(configuration, UserMapper.class, "selectById", Map.of("id", 1L)))
                .contains("recovery_code_hash, recovery_code_created_at");
        assertThat(sql(configuration, UserMapper.class, "selectByPhone", Map.of("phone", "13800005678")))
                .contains("recovery_code_hash, recovery_code_created_at");

        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 9, 0);
        Map<String, Object> recovery = new HashMap<>();
        recovery.put("id", 1L);
        recovery.put("phone", "13900001111");
        recovery.put("passwordHash", "$2a$10$hash");
        recovery.put("clearRecoveryCode", true);
        recovery.put("now", now);
        assertThat(sql(configuration, UserMapper.class, "updateForRebind", recovery))
                .contains("SET phone = ?, password_hash = ?, recovery_code_hash = NULL, recovery_code_created_at = NULL,"
                        + " token_version = token_version + 1, last_login_at = ?, sms_verified_at = ?, updated_at = ? WHERE id = ?");

        Map<String, Object> passwordOnly = new HashMap<>(recovery);
        passwordOnly.put("passwordHash", null);
        passwordOnly.put("clearRecoveryCode", false);
        String phoneOnly = sql(configuration, UserMapper.class, "updateForRebind", passwordOnly);
        assertThat(phoneOnly).contains("SET phone = ?, token_version = token_version + 1, last_login_at = ?")
                .doesNotContain("password_hash").doesNotContain("recovery_code_hash");

        // 验证码登录 / 注册记录「刚验证过短信」；查询列包含 sms_verified_at
        assertThat(sql(configuration, UserMapper.class, "markSmsVerified", Map.of("id", 1L, "now", now)))
                .contains("SET sms_verified_at = ?, updated_at = ? WHERE id = ?");
        assertThat(sql(configuration, UserMapper.class, "selectById", Map.of("id", 1L))).contains("sms_verified_at");

        Map<String, Object> code = new HashMap<>();
        code.put("id", 1L);
        code.put("recoveryCodeHash", "$2a$10$hash");
        code.put("now", now);
        BoundSql update = configuration.getMappedStatement(UserMapper.class.getName() + ".updateRecoveryCode")
                .getBoundSql(code);
        assertThat(update.getSql().replaceAll("\\s+", " "))
                .contains("SET recovery_code_hash = ?, recovery_code_created_at = ?, updated_at = ? WHERE id = ?");
        assertThat(update.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("recoveryCodeHash", "now", "now", "id");

        ResetTicket ticket = new ResetTicket();
        ticket.setPurpose(TicketPurpose.REBIND_RECOVERY);
        BoundSql insert = configuration.getMappedStatement(ResetTicketMapper.class.getName() + ".insert")
                .getBoundSql(ticket);
        assertThat(insert.getSql()).contains("purpose");
        assertThat(insert.getParameterMappings()).extracting(ParameterMapping::getProperty).contains("purpose");
        assertThat(sql(configuration, ResetTicketMapper.class, "selectByHash", Map.of("ticketHash", "h")))
                .contains("ticket_hash, purpose, expire_at");
    }

    /** 清理头像：按相对路径批量核对 t_user.avatar_url 引用；更新资料时 avatarUrl 为 null 不改头像 */
    @Test
    void avatarStatementsAreBound() throws Exception {
        Configuration configuration = parseAll();

        Map<String, Object> urls = new HashMap<>();
        urls.put("urls", List.of("avatar/20261012/a.jpg", "avatar/20261012/b.png"));
        BoundSql inUse = configuration.getMappedStatement(UserMapper.class.getName() + ".selectAvatarUrlsInUse")
                .getBoundSql(urls);
        assertThat(inUse.getSql().replaceAll("\\s+", " "))
                .contains("SELECT DISTINCT avatar_url FROM t_user WHERE avatar_url IN ( ? , ? )");
        assertThat(inUse.getParameterMappings()).hasSize(2);

        User keep = new User();
        keep.setId(1L);
        assertThat(sql(configuration, UserMapper.class, "updateProfile", keep)).doesNotContain("avatar_url");
        keep.setAvatarUrl("avatar/20261014/new.jpg");
        assertThat(sql(configuration, UserMapper.class, "updateProfile", keep)).contains("avatar_url = ?");
    }

    /** 后台统计：时间范围 [from, to) 两端都绑定参数，按天分组，活跃用户三类来源 UNION 去重 */
    @Test
    void adminStatsStatementsAreBound() throws Exception {
        Configuration configuration = parseAll();
        Map<String, Object> range = new HashMap<>();
        range.put("from", LocalDateTime.of(2026, 10, 8, 0, 0));
        range.put("to", LocalDateTime.of(2026, 10, 15, 0, 0));

        BoundSql active = configuration.getMappedStatement(AdminStatsMapper.class.getName() + ".countActiveUsersBetween")
                .getBoundSql(range);
        String activeSql = active.getSql().replaceAll("\\s+", " ");
        assertThat(activeSql).contains("FROM t_recognition r WHERE r.created_at >= ? AND r.created_at < ?")
                .contains("UNION SELECT s.user_id FROM t_ai_message m JOIN t_ai_session s ON m.session_id = s.id")
                .contains("m.`role` = 'user'")
                .contains("u.last_login_at >= ? AND u.last_login_at < ?");
        assertThat(active.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("from", "to", "from", "to", "from", "to");

        assertThat(sql(configuration, AdminStatsMapper.class, "dailyActiveUsers", range))
                .contains("SELECT a.stat_date, COUNT(*) AS total")
                .contains("SELECT DATE(r.created_at) AS stat_date, r.user_id")
                .contains("GROUP BY a.stat_date");
        assertThat(sql(configuration, AdminStatsMapper.class, "dailyRecognitions", range))
                .contains("DATE(created_at) AS stat_date")
                .contains("SUM(CASE WHEN `status` = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success")
                .contains("GROUP BY DATE(created_at)");
        assertThat(sql(configuration, AdminStatsMapper.class, "questionsBetween", range))
                .contains("AS part").contains("WHERE `role` = 'user' AND created_at >= ? AND created_at < ?");
        assertThat(sql(configuration, AdminStatsMapper.class, "dailySms", range)).contains("FROM t_sms_code");
        assertThat(sql(configuration, AdminStatsMapper.class, "topResults", Map.of("limit", 10)))
                .contains("WHERE `status` = 'SUCCESS'").contains("ORDER BY total DESC, name ASC LIMIT ?");

        for (String id : List.of("dailyNewUsers", "dailyActiveUsers", "dailyRecognitions", "dailyQuestions", "dailySms")) {
            assertThat(configuration.getMappedStatement(AdminStatsMapper.class.getName() + "." + id)
                    .getResultMaps().get(0).getType()).isEqualTo(DailyCount.class);
        }
        assertThat(configuration.getMappedStatement(AdminStatsMapper.class.getName() + ".recognitionsBetween")
                .getResultMaps().get(0).getType()).isEqualTo(RangeCount.class);
        assertThat(configuration.getMappedStatement(AdminStatsMapper.class.getName() + ".countByMode")
                .getResultMaps().get(0).getType()).isEqualTo(NameCount.class);
    }

    /** 后台用户管理与管理员：动态条件按参数取舍；失败计数先判断锁定再更新次数 */
    @Test
    void adminUserAndAuthStatementsAreBound() throws Exception {
        Configuration configuration = parseAll();

        Map<String, Object> filter = new HashMap<>();
        filter.put("keyword", "138");
        filter.put("status", 1);
        filter.put("offset", 0L);
        filter.put("size", 20);
        String page = sql(configuration, AdminUserMapper.class, "selectUserPage", filter);
        assertThat(page).contains("u.phone LIKE CONCAT('%', ?, '%') OR u.nickname LIKE CONCAT('%', ?, '%')")
                .contains("AND u.`status` = ?")
                .contains("ORDER BY u.id DESC LIMIT ?, ?")
                .contains("AS recognition_count").contains("AS ai_question_count").contains("AS recovery_code_generated")
                .doesNotContain("password_hash").doesNotContain("token_version");
        Map<String, Object> none = new HashMap<>(filter);
        none.put("keyword", null);
        none.put("status", null);
        assertThat(sql(configuration, AdminUserMapper.class, "countUsers", none).trim())
                .isEqualTo("SELECT COUNT(*) FROM t_user u");
        none.put("status", 0);
        assertThat(sql(configuration, AdminUserMapper.class, "countUsers", none))
                .contains("WHERE u.`status` = ?").doesNotContain("AND u.`status`");
        assertThat(configuration.getMappedStatement(AdminUserMapper.class.getName() + ".selectUserRow")
                .getResultMaps().get(0).getType()).isEqualTo(AdminUserRow.class);

        Map<String, Object> status = new HashMap<>();
        status.put("id", 1L);
        status.put("status", 1);
        status.put("bumpTokenVersion", true);
        status.put("now", LocalDateTime.of(2026, 10, 14, 9, 0));
        assertThat(sql(configuration, AdminUserMapper.class, "updateStatus", status))
                .contains("SET `status` = ?, token_version = token_version + 1, updated_at = ? WHERE id = ?");
        status.put("bumpTokenVersion", false);
        assertThat(sql(configuration, AdminUserMapper.class, "updateStatus", status)).doesNotContain("token_version");

        Map<String, Object> profile = new HashMap<>();
        profile.put("id", 1L);
        profile.put("nickname", "老李");
        profile.put("identity", null);
        profile.put("clearIdentity", false);
        profile.put("crops", null);
        profile.put("updateCrops", true);
        profile.put("clearAvatar", true);
        profile.put("now", LocalDateTime.of(2026, 10, 14, 9, 0));
        assertThat(sql(configuration, AdminUserMapper.class, "updateProfile", profile))
                .contains("SET nickname = ?, crops = ?, avatar_url = NULL, updated_at = ?")
                .doesNotContain("`identity`");
        profile.put("identity", "种植户");
        profile.put("updateCrops", false);
        profile.put("clearAvatar", false);
        assertThat(sql(configuration, AdminUserMapper.class, "updateProfile", profile))
                .contains("SET nickname = ?, `identity` = ?, updated_at = ?")
                .doesNotContain("avatar_url").doesNotContain("crops");
        profile.put("identity", null);
        profile.put("clearIdentity", true);
        assertThat(sql(configuration, AdminUserMapper.class, "updateProfile", profile))
                .contains("SET nickname = ?, `identity` = NULL, updated_at = ?");
        assertThat(sql(configuration, AdminUserMapper.class, "selectRecognitionPage",
                Map.of("userId", 1L, "offset", 0L, "size", 20)))
                .contains("deleted").contains("WHERE user_id = ? ORDER BY created_at DESC, id DESC");

        Map<String, Object> failure = new HashMap<>();
        failure.put("id", 1L);
        failure.put("threshold", 5);
        failure.put("lockUntil", LocalDateTime.of(2026, 10, 14, 9, 15));
        failure.put("now", LocalDateTime.of(2026, 10, 14, 9, 0));
        String recordFailure = sql(configuration, AdminMapper.class, "recordFailure", failure);
        // MySQL 从左到右赋值：locked_until 必须在 failed_attempts 之前
        assertThat(recordFailure.indexOf("locked_until = CASE")).isLessThan(recordFailure.indexOf("failed_attempts = CASE"));
        assertThat(configuration.getMappedStatement(AdminMapper.class.getName() + ".selectByUsername")
                .getResultMaps().get(0).getType()).isEqualTo(Admin.class);
        assertThat(sql(configuration, AdminMapper.class, "updatePassword",
                Map.of("id", 1L, "passwordHash", "h", "now", LocalDateTime.of(2026, 10, 14, 9, 0))))
                .contains("must_change_password = 0");

        Map<String, Object> logs = new HashMap<>();
        logs.put("action", null);
        logs.put("offset", 0L);
        logs.put("size", 20);
        assertThat(sql(configuration, AdminLogMapper.class, "selectPage", logs)).doesNotContain("WHERE")
                .contains("ORDER BY created_at DESC, id DESC LIMIT ?, ?");
        logs.put("action", "LOGIN");
        assertThat(sql(configuration, AdminLogMapper.class, "selectPage", logs)).contains("WHERE action = ?");
        assertThat(configuration.getMappedStatement(AdminLogMapper.class.getName() + ".insert")
                .getBoundSql(new AdminLog()).getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("adminId", "adminName", "action", "target", "detail", "ip", "createdAt");

        BoundSql upsert = configuration.getMappedStatement(SystemConfigMapper.class.getName() + ".upsert")
                .getBoundSql(new SystemConfig());
        assertThat(upsert.getSql().replaceAll("\\s+", " "))
                .contains("ON DUPLICATE KEY UPDATE config_value = incoming.config_value");
        assertThat(upsert.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("configKey", "configValue", "secret", "updatedBy", "updatedAt");
    }

    /** 模型自动备用（迁移 007）：消息与识别记录写入 / 读取 model、fallback；后台统计按 fallback = 1 计数 */
    @Test
    void modelFallbackStatementsAreBound() throws Exception {
        Configuration configuration = parseAll();
        AiMessage answer = new AiMessage(9L, "assistant", "答", null, LocalDateTime.of(2026, 10, 14, 10, 0));
        answer.setModel("qwen3.8-max");
        answer.setFallback(true);
        BoundSql insert = configuration.getMappedStatement(AiMessageMapper.class.getName() + ".insert").getBoundSql(answer);
        assertThat(insert.getSql()).contains("model, fallback");
        assertThat(insert.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .contains("model", "fallback");
        // 用户消息不设置时 fallback 默认为 false（列为 NOT NULL）
        assertThat(new AiMessage(9L, "user", "问", null, LocalDateTime.of(2026, 10, 14, 10, 0)).getFallback()).isFalse();
        assertThat(sql(configuration, AiMessageMapper.class, "selectBySession", Map.of("sessionId", 9L)))
                .contains("reasoning_ms, model, fallback, created_at");

        Recognition recognition = new Recognition();
        BoundSql recInsert = configuration.getMappedStatement(RecognitionMapper.class.getName() + ".insert")
                .getBoundSql(recognition);
        assertThat(recInsert.getSql().replaceAll("\\s+", " ")).contains("model_name, model, fallback, deleted");
        assertThat(recInsert.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .contains("modelName", "model", "fallback");
        assertThat(recognition.getFallback()).isFalse();
        BoundSql update = configuration.getMappedStatement(RecognitionMapper.class.getName() + ".updateResult")
                .getBoundSql(recognition);
        assertThat(update.getSql().replaceAll("\\s+", " ")).contains("model = ?, fallback = ?");

        Map<String, Object> failure = new HashMap<>();
        failure.put("id", 1L);
        failure.put("userId", 2L);
        failure.put("status", "IMAGE_BAD");
        failure.put("failReason", "IMAGE_BAD");
        failure.put("model", "deepseek-flash");
        failure.put("fallback", true);
        failure.put("now", LocalDateTime.of(2026, 10, 14, 10, 0));
        BoundSql updateFailure = configuration.getMappedStatement(RecognitionMapper.class.getName() + ".updateFailure")
                .getBoundSql(failure);
        assertThat(updateFailure.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("status", "failReason", "model", "fallback", "now", "id", "userId");

        assertThat(sql(configuration, AdminUserMapper.class, "selectRecognitionPage",
                Map.of("userId", 1L, "offset", 0L, "size", 20))).contains("model, fallback");

        Map<String, Object> range = new HashMap<>();
        range.put("from", LocalDateTime.of(2026, 10, 8, 0, 0));
        range.put("to", LocalDateTime.of(2026, 10, 15, 0, 0));
        assertThat(sql(configuration, AdminStatsMapper.class, "countFallbackAnswersBetween", range))
                .contains("WHERE `role` = 'assistant' AND fallback = 1 AND created_at >= ? AND created_at < ?");
        assertThat(sql(configuration, AdminStatsMapper.class, "countFallbackRecognitionsBetween", range))
                .contains("FROM t_recognition WHERE fallback = 1 AND created_at >= ? AND created_at < ?");
        BoundSql daily = configuration.getMappedStatement(AdminStatsMapper.class.getName() + ".dailyFallbackUsed")
                .getBoundSql(range);
        assertThat(daily.getSql().replaceAll("\\s+", " ")).contains("UNION ALL").contains("GROUP BY f.stat_date");
        assertThat(daily.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("from", "to", "from", "to");
        assertThat(configuration.getMappedStatement(AdminStatsMapper.class.getName() + ".dailyFallbackUsed")
                .getResultMaps().get(0).getType()).isEqualTo(DailyCount.class);
    }

    @Test
    void everyMapperMethodHasXmlStatement() throws Exception {
        Configuration configuration = parseAll();

        List<Class<?>> mappers = List.of(UserMapper.class, SmsCodeMapper.class, ResetTicketMapper.class,
                RecognitionMapper.class, AiSessionMapper.class, AiMessageMapper.class, AppVersionMapper.class,
                SystemConfigMapper.class, AdminMapper.class, AdminLogMapper.class, AdminStatsMapper.class,
                AdminUserMapper.class);
        List<String> missing = new ArrayList<>();
        for (Class<?> mapper : mappers) {
            for (Method method : mapper.getDeclaredMethods()) {
                String id = mapper.getName() + "." + method.getName();
                if (!method.isDefault() && !configuration.hasStatement(id, false)) {
                    missing.add(id);
                }
            }
        }
        assertThat(missing).isEmpty();
    }
}
