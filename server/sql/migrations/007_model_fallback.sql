-- =====================================================================
-- 迁移 007：模型自动备用 —— t_ai_message、t_recognition 新增 model（实际给出结果的模型名）与 fallback（是否由备用模型完成）
-- 依据：docs/后台管理接口.md「八、模型自动备用」
-- 适用：按旧版 sql/schema.sql 建好的已有库（新建库直接执行最新的 schema.sql，已包含以下变更，无需本脚本）
-- 用法：mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/007_model_fallback.sql
--       PowerShell：Get-Content sql/migrations/007_model_fallback.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4 pestai
-- 说明：可重复执行 —— 加列前先查 information_schema（MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS），已存在则跳过，
--       误执行第二次也不会报错。只新增列，不修改、不删除任何已有数据：已有记录的 model 为 NULL、fallback 为 0
--       （后台显示为「未知模型、非备用」，统计中按非备用计）。
--       model 与 fallback 只在后台显示与统计，App 接口不返回（识别结果的 model_name 仍为「通义千问」）。
--       必须在部署新版本服务端之前执行：新版本保存 AI 回答、提问与识别记录时都会写这两列，未迁移的库会让 AI 问答、
--       拍照识别全部失败；后台的统计、对话与识别记录也会读这两列。
-- =====================================================================

-- 数据库名不是 pestai 时改成实际库名
USE pestai;

-- 1. t_ai_message：AI 回答（role = assistant）所用的模型与是否备用；用户消息为 NULL / 0
SET @pestai_has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_message' AND COLUMN_NAME = 'model');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_ai_message ADD COLUMN model VARCHAR(64) NULL COMMENT ''生成回答的模型名（如 deepseek-flash、qwen3.8-max，Mock 为 mock）；只有 assistant 消息有值，用户消息与旧数据为空'' AFTER reasoning_ms',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

SET @pestai_has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_message' AND COLUMN_NAME = 'fallback');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_ai_message ADD COLUMN fallback TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1 由备用模型回答（主模型 DeepSeek 失败后改用通义千问） / 0 否'' AFTER model',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 2. t_recognition：给出结果（成功 / 照片不清晰）的模型与是否备用；识别失败为 NULL / 0
SET @pestai_has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_recognition' AND COLUMN_NAME = 'model');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_recognition ADD COLUMN model VARCHAR(64) NULL COMMENT ''给出结果的模型名（如 qwen3.8-max、deepseek-flash，Mock 为 mock）；成功与照片不清晰时有值，失败与旧数据为空'' AFTER model_name',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

SET @pestai_has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_recognition' AND COLUMN_NAME = 'fallback');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_recognition ADD COLUMN fallback TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1 由备用模型给出结果（主模型通义千问失败后改用 DeepSeek） / 0 否'' AFTER model',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 执行后可检查：
--   SHOW FULL COLUMNS FROM t_ai_message WHERE Field IN ('model', 'fallback');
--   SHOW FULL COLUMNS FROM t_recognition WHERE Field IN ('model', 'fallback');
