-- =====================================================================
-- 迁移 001：旧版换绑表 t_appeal 新增 new_password_hash；验证码新增 CHANGE_PHONE（更换手机号）、DEACTIVATE（注销账号）场景
-- 依据：docs/新增功能-更换手机号与注销账号.md「四、数据表变更」
-- 适用：按旧版 sql/schema.sql 建好的已有库（新建库直接执行最新的 schema.sql，无需本脚本）
-- 用法：mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/001_appeal_new_password.sql
--       PowerShell：Get-Content sql/migrations/001_appeal_new_password.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4 pestai
-- 说明：只新增一个可空列并修改列注释，不改动任何已有数据；新增列前先查 information_schema
--       （MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS），误执行第二次也不会报错。
--       t_appeal 已由迁移 003 整表删除：本脚本在该表不存在时跳过第 1 步，按顺序执行 001 → 002 → 003 即可；
--       第 2、3 步的列注释会被 003 再次更新。
-- =====================================================================

-- 数据库名不是 pestai 时改成实际库名
USE pestai;

-- 1. t_appeal（旧版换绑表，已由 003 删除）：新增 new_password_hash；表不存在或列已存在时跳过
SET @pestai_has_table := (SELECT COUNT(*)
                          FROM information_schema.TABLES
                          WHERE TABLE_SCHEMA = DATABASE()
                            AND TABLE_NAME = 't_appeal');
SET @pestai_has_col := (SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 't_appeal'
                          AND COLUMN_NAME = 'new_password_hash');
SET @pestai_ddl := IF(@pestai_has_table = 1 AND @pestai_has_col = 0,
    'ALTER TABLE t_appeal ADD COLUMN new_password_hash VARCHAR(100) NULL COMMENT ''换绑时设置的新密码（BCrypt）'' AFTER new_phone',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 2. t_sms_code.scene：取值增加 CHANGE_PHONE、DEACTIVATE；列仍为 VARCHAR(16)（最长的 CHANGE_PHONE 为 12 个字符），只更新注释
--    （003 会把注释更新为最新的场景列表）
ALTER TABLE t_sms_code
    MODIFY COLUMN scene VARCHAR(16) NOT NULL COMMENT 'LOGIN / REGISTER / RESET / REBIND / CHANGE_PHONE / DEACTIVATE';

-- 3. t_user.token_version：更换手机号时同样加 1，只更新注释
ALTER TABLE t_user
    MODIFY COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT '令牌版本号，重置密码 / 找回账号换绑 / 更换手机号 / 退出登录时加 1';

-- 执行后可检查：SHOW FULL COLUMNS FROM t_sms_code LIKE 'scene';
