-- =====================================================================
-- 迁移 006：后台管理 —— 新增管理员表 t_admin、操作日志表 t_admin_log、系统配置表 t_system_config；
--           t_user.identity 改为可空（后台可清除种植身份）；并为后台数据统计（按天统计新增用户、活跃用户、识别次数、
--           AI 提问、短信发送）补充按时间的索引
-- 依据：docs/后台管理接口.md「一、登录与安全」「二、数据统计」「四、系统配置」「五、操作日志」
-- 适用：按旧版 sql/schema.sql 建好的已有库（新建库直接执行最新的 schema.sql，已包含以下变更，无需本脚本）
-- 用法：mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/006_admin.sql
--       PowerShell：Get-Content sql/migrations/006_admin.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4 pestai
-- 说明：可重复执行 —— 建表用 CREATE TABLE IF NOT EXISTS；改列、加索引前先查 information_schema
--       （MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS），误执行第二次也不会报错。不修改、不删除任何已有数据
--       （t_user.identity 只放宽为可空，已有的值不变）。
--       管理员账号不在本脚本中创建：服务端启动时发现 t_admin 为空，自动创建 admin（密码取环境变量
--       PESTAI_ADMIN_INITIAL_PASSWORD，未设置时随机生成并在启动日志中打印一次），首次登录后必须修改密码。
--       请在部署新版本服务端之前执行（未迁移时 App 接口不受影响，但后台无法登录、后台修改的配置无法保存）。
-- =====================================================================

-- 数据库名不是 pestai 时改成实际库名
USE pestai;

-- 1. 管理员（与 schema.sql 一致）
CREATE TABLE IF NOT EXISTS t_admin (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    username              VARCHAR(32)  NOT NULL COMMENT '登录名，初始管理员为 admin',
    password_hash         VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希（自带盐值）',
    must_change_password  TINYINT      NOT NULL DEFAULT 1 COMMENT '1 必须先修改初始密码 / 0 已修改',
    failed_attempts       INT          NOT NULL DEFAULT 0 COMMENT '连续输错密码次数（登录成功或被锁定时清零）',
    locked_until          DATETIME     NULL COMMENT '连续输错 5 次后锁定到该时间（15 分钟）',
    last_login_at         DATETIME     NULL COMMENT '最近登录时间',
    last_login_ip         VARCHAR(64)  NULL COMMENT '最近登录 IP',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '后台管理员';

-- 2. 后台操作日志（登录成功 / 失败、退出与全部写操作；detail 中不写密钥明文与用户密码）
CREATE TABLE IF NOT EXISTS t_admin_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    admin_id    BIGINT        NULL COMMENT '管理员 ID；登录失败且用户名不存在时为空',
    admin_name  VARCHAR(32)   NOT NULL COMMENT '管理员用户名（登录失败时为尝试登录的用户名）',
    action      VARCHAR(32)   NOT NULL COMMENT 'LOGIN / LOGIN_FAILED / LOGOUT / CHANGE_PASSWORD / USER_DISABLE / USER_ENABLE / USER_LOGOUT / USER_PROFILE / USER_RESET_PASSWORD / USER_DEACTIVATE / RECOGNITION_DELETE / SESSION_DELETE / CONFIG_UPDATE / CONFIG_RESET / CONFIG_TEST',
    target      VARCHAR(128)  NULL COMMENT '操作对象，如「用户 10001」「配置 VISION_MODEL」',
    detail      VARCHAR(500)  NULL COMMENT '摘要',
    ip          VARCHAR(64)   NULL COMMENT '客户端 IP（经 Nginx 时取 X-Real-IP）',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_created (created_at),
    KEY idx_action_created (action, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '后台操作日志';

-- 3. 系统配置：后台设置的 API Key、模型名、短信签名与模板，优先于环境变量 / .env；密钥类 AES-GCM 加密保存
CREATE TABLE IF NOT EXISTS t_system_config (
    config_key    VARCHAR(64)  NOT NULL COMMENT '与环境变量同名，如 DASHSCOPE_API_KEY、VISION_MODEL',
    config_value  TEXT         NOT NULL COMMENT '配置值；secret = 1 时为密文（v1:Base64(IV + 密文)）',
    secret        TINYINT      NOT NULL DEFAULT 0 COMMENT '1 密钥类（加密保存，后台只显示掩码）',
    updated_by    VARCHAR(32)  NULL COMMENT '最后修改的管理员',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统配置（后台设置，优先于环境变量）';

-- 4. t_user.identity 改为可空：后台可以把用户的种植身份清除为「未设置」（NULL）；默认值仍为「种植户」，
--    App 注册 / 完善资料的行为不变（App 读到空身份时按「种植户」显示，AI 画像按没有填写处理）。已是可空时跳过
SET @pestai_not_null := (SELECT COUNT(*) FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'identity'
                           AND IS_NULLABLE = 'NO');
SET @pestai_ddl := IF(@pestai_not_null > 0,
    'ALTER TABLE t_user MODIFY COLUMN `identity` VARCHAR(16) NULL DEFAULT ''种植户'' COMMENT ''种植户 / 家庭园艺 / 农技人员；后台清除后为 NULL（未设置）''',
    'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 5. 后台统计用的时间索引（已存在则跳过）
--    t_user.created_at（每日新增）、t_user.last_login_at（活跃用户）
SET @pestai_has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND INDEX_NAME = 'idx_created');
SET @pestai_ddl := IF(@pestai_has_idx = 0, 'ALTER TABLE t_user ADD INDEX idx_created (created_at)', 'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

SET @pestai_has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND INDEX_NAME = 'idx_last_login');
SET @pestai_ddl := IF(@pestai_has_idx = 0, 'ALTER TABLE t_user ADD INDEX idx_last_login (last_login_at)', 'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

--    t_recognition.created_at（每日识别次数、成功率；原有索引都以 user_id 开头，按全站时间范围统计用不上）
SET @pestai_has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_recognition' AND INDEX_NAME = 'idx_created');
SET @pestai_ddl := IF(@pestai_has_idx = 0, 'ALTER TABLE t_recognition ADD INDEX idx_created (created_at)', 'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

--    t_ai_message (role, created_at)（每日 AI 提问数：role = 'user' 且按时间范围）
SET @pestai_has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_message' AND INDEX_NAME = 'idx_role_created');
SET @pestai_ddl := IF(@pestai_has_idx = 0, 'ALTER TABLE t_ai_message ADD INDEX idx_role_created (`role`, created_at)', 'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

--    t_sms_code.created_at（每日短信发送数：每发送一条验证码写一行）
SET @pestai_has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_sms_code' AND INDEX_NAME = 'idx_created');
SET @pestai_ddl := IF(@pestai_has_idx = 0, 'ALTER TABLE t_sms_code ADD INDEX idx_created (created_at)', 'DO 0');
PREPARE pestai_stmt FROM @pestai_ddl;
EXECUTE pestai_stmt;
DEALLOCATE PREPARE pestai_stmt;

-- 执行后可检查：
--   SHOW TABLES LIKE 't_admin%';  SHOW TABLES LIKE 't_system_config';
--   SHOW FULL COLUMNS FROM t_user LIKE 'identity';   -- Null 为 YES
--   SHOW INDEX FROM t_user WHERE Key_name IN ('idx_created', 'idx_last_login');
--   SHOW INDEX FROM t_recognition WHERE Key_name = 'idx_created';
--   SHOW INDEX FROM t_ai_message WHERE Key_name = 'idx_role_created';
--   SHOW INDEX FROM t_sms_code WHERE Key_name = 'idx_created';
