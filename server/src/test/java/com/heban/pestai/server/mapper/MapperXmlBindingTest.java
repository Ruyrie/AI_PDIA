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

import com.heban.pestai.server.common.TicketPurpose;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSessionSummary;
import com.heban.pestai.server.entity.ResetTicket;

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
        assertThat(inUse.getParameterMappings()).hasSize(2);

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

    @Test
    void everyMapperMethodHasXmlStatement() throws Exception {
        Configuration configuration = parseAll();

        List<Class<?>> mappers = List.of(UserMapper.class, SmsCodeMapper.class, ResetTicketMapper.class,
                RecognitionMapper.class, AiSessionMapper.class, AiMessageMapper.class, AppVersionMapper.class);
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
