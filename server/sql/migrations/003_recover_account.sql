-- =====================================================================
-- 迁移 003：找回账号（登录密码 / 账号恢复码验证身份后换绑新手机号）与账号恢复码；删除旧版换绑表 t_appeal
-- 依据：docs/找回账号与恢复码.md「三、数据表」
-- 适用：按旧版 sql/schema.sql 建好的已有库（新建库直接执行最新的 schema.sql，已包含以下变更，无需本脚本）
-- 用法：mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/003_recover_account.sql
--       PowerShell：Get-Content sql/migrations/003_recover_account.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4 pestai
-- 说明：可重复执行 —— 删表用 DROP TABLE IF EXISTS；加列前先查 information_schema
--       （MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS），列已存在时跳过；其余只改列 / 表注释。
--       除删除 t_appeal 整表（其中的记录不再使用，需要留档请先备份）外，不改动任何已有数据：
--       已有的 t_reset_ticket 记录都是找回密码凭证，新列 purpose 取默认值 RESET；已有用户恢复码为空（未生成）。
--       请在部署新版本服务端之前执行（新版本读写 t_user.recovery_code_hash / recovery_code_created_at、
--       t_reset_ticket.purpose，未迁移的库会让登录、找回密码等接口全部报错）。
-- =====================================================================

-- 数据库名不是 pestai 时改成实际库名
USE pestai;

-- 1. 删除旧版换绑表 t_appeal（对应的接口与代码已全部删除）
DROP TABLE IF EXISTS t_appeal;

-- 2. t_user：新增 recovery_code_hash（账号恢复码的 BCrypt 哈希）
SET @pestai_has_col := (SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 't_user'
                          AND COLUMN_NAME = 'recovery_code_hash');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_user ADD COLUMN recovery_code_hash VARCHAR(100) NULL COMMENT ''账号恢复码的 BCrypt 哈希；未生成或已用于找回账号后为空'' AFTER salt',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 3. t_user：新增 recovery_code_created_at（恢复码生成时间）
SET @pestai_has_col := (SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 't_user'
                          AND COLUMN_NAME = 'recovery_code_created_at');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_user ADD COLUMN recovery_code_created_at DATETIME NULL COMMENT ''恢复码生成时间'' AFTER recovery_code_hash',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 4. t_reset_ticket：新增 purpose（RESET 找回密码 / REBIND_PASSWORD、REBIND_RECOVERY 找回账号）
SET @pestai_has_col := (SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 't_reset_ticket'
                          AND COLUMN_NAME = 'purpose');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_reset_ticket ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT ''RESET'' COMMENT ''RESET 找回密码 / REBIND_PASSWORD、REBIND_RECOVERY 找回账号（分别用登录密码、恢复码验证）'' AFTER ticket_hash',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 5. t_reset_ticket：更新列注释与表注释（找回密码与找回账号共用）
ALTER TABLE t_reset_ticket
    MODIFY COLUMN phone VARCHAR(11) NOT NULL COMMENT '生成凭证时的手机号（找回账号为原手机号）',
    COMMENT = '找回密码 / 找回账号一次性凭证';

-- 6. t_sms_code.scene：APPEAL 改名 REBIND，新增 RECOVERY；列仍为 VARCHAR(16)，只更新注释
--    （迁移前发出的 APPEAL 验证码 5 分钟后自然过期，新版本不再读取）
ALTER TABLE t_sms_code
    MODIFY COLUMN scene VARCHAR(16) NOT NULL COMMENT 'LOGIN / REGISTER / RESET / REBIND / CHANGE_PHONE / DEACTIVATE / RECOVERY';

-- 7. t_user.token_version：找回账号换绑时加 1，只更新注释
ALTER TABLE t_user
    MODIFY COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT '令牌版本号，重置密码 / 找回账号换绑 / 更换手机号 / 退出登录时加 1';

-- 执行后可检查：
--   SHOW TABLES LIKE 't_appeal';                    -- 应为空
--   SHOW FULL COLUMNS FROM t_user WHERE Field IN ('recovery_code_hash', 'recovery_code_created_at');
--   SHOW FULL COLUMNS FROM t_reset_ticket LIKE 'purpose';
