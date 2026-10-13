-- 004：t_user 新增 sms_verified_at（最近一次通过短信验证码证明手机号归属的时间）。
-- 用于「刚验证过短信 10 分钟内生成恢复码不需要再次短信验证」，避免阿里云同号 60 秒只发 1 条导致「操作太频繁」。
-- 可重复执行。
USE pestai;

SET @pestai_has_col := (SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 't_user'
                          AND COLUMN_NAME = 'sms_verified_at');
SET @pestai_ddl := IF(@pestai_has_col = 0,
    'ALTER TABLE t_user ADD COLUMN sms_verified_at DATETIME NULL COMMENT ''最近一次通过短信验证码证明手机号归属的时间（10 分钟内生成恢复码免再次验证）'' AFTER last_login_at',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;
