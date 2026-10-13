-- =====================================================================
-- 迁移 002：AI 问答保存思考过程；历史对话按最后一条消息时间排序
-- 依据：docs/AI问答交互优化.md「三、数据与接口」
-- 适用：按旧版 sql/schema.sql 建好的已有库（新建库直接执行最新的 schema.sql，已包含以下变更，无需本脚本）
-- 用法：mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/002_ai_reasoning_and_history.sql
--       PowerShell：Get-Content sql/migrations/002_ai_reasoning_and_history.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4 pestai
-- 说明：只新增两个可空列和一个索引，不改动任何已有数据；加列、加索引前先查 information_schema
--       （MySQL 8.0 不支持 ADD COLUMN / ADD INDEX IF NOT EXISTS），误执行第二次也不会报错。
--       迁移前保存的消息 reasoning_content、reasoning_ms 为空（App 不显示思考区）。
--       请在部署新版本服务端之前执行（新版本读写 t_ai_message.reasoning_content / reasoning_ms）。
-- =====================================================================

-- 数据库名不是 pestai 时改成实际库名
USE pestai;

-- 1. t_ai_message：新增 reasoning_content（思考过程全文）
SET @pestai_has_col := (SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 't_ai_message'
                          AND COLUMN_NAME = 'reasoning_content');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_ai_message ADD COLUMN reasoning_content MEDIUMTEXT NULL COMMENT ''思考过程全文（DeepSeek reasoning_content；没有思考时为空）'' AFTER image_url',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 2. t_ai_message：新增 reasoning_ms（从开始请求上游到收到第一个正文片段的毫秒数，只在有思考内容时记录）
SET @pestai_has_col := (SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 't_ai_message'
                          AND COLUMN_NAME = 'reasoning_ms');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_ai_message ADD COLUMN reasoning_ms INT NULL COMMENT ''从开始请求上游到收到第一个正文片段的毫秒数（只在有思考内容时记录）'' AFTER reasoning_content',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 3. t_ai_session：新增索引 idx_user_updated (user_id, updated_at)，历史对话按 updated_at 倒序分页
SET @pestai_has_idx := (SELECT COUNT(*)
                        FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 't_ai_session'
                          AND INDEX_NAME = 'idx_user_updated');
SET @pestai_ddl := IF(@pestai_has_idx = 0,
    'ALTER TABLE t_ai_session ADD INDEX idx_user_updated (user_id, updated_at)',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 执行后可检查：
--   SHOW FULL COLUMNS FROM t_ai_message WHERE Field IN ('reasoning_content', 'reasoning_ms');
--   SHOW INDEX FROM t_ai_session WHERE Key_name = 'idx_user_updated';
