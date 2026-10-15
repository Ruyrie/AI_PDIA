-- =====================================================================
-- AI 病虫害识别助手 · 数据库建表脚本（MySQL 8.0+，InnoDB，utf8mb4）
-- 依据：公共数据/04-后端接口与数据表.md「三、数据表」及各模块技术准备文档
-- 用法：mysql -u root -p < sql/schema.sql
--       新建库直接执行本文件即可；已按旧版本建好的库请执行 sql/migrations/ 下尚未执行过的迁移脚本
-- 说明：每张表都有 id（BIGINT 自增主键）、created_at、updated_at（写入后不再修改的 t_ai_message_image、t_admin_log 只有 created_at；
--       t_system_config 以 config_key 为主键，只有 updated_at）；应用写库时由服务端按 Asia/Shanghai 填写时间，列默认值只作兜底。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS pestai
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE pestai;

-- ---------------------------------------------------------------------
-- 用户
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user (
    id                        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    phone                     VARCHAR(11)  NOT NULL COMMENT '手机号',
    password_hash             VARCHAR(100) NULL COMMENT 'BCrypt 哈希（自带盐值）；验证码自动注册且未设置密码时为空',
    salt                      VARCHAR(64)  NULL COMMENT '保留列，BCrypt 不使用，可为空',
    recovery_code_hash        VARCHAR(100) NULL COMMENT '账号恢复码的 BCrypt 哈希；未生成或已用于找回账号后为空',
    recovery_code_created_at  DATETIME     NULL COMMENT '恢复码生成时间',
    nickname                  VARCHAR(32)  NOT NULL COMMENT '昵称，最多 16 字',
    avatar_url                VARCHAR(255) NULL COMMENT '头像相对路径，如 avatar/20261013/{uuid}.jpg',
    region                    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '地区',
    `identity`                VARCHAR(16)  NULL DEFAULT '种植户' COMMENT '种植户 / 家庭园艺 / 农技人员；后台清除后为 NULL（未设置）',
    crops                     VARCHAR(255) NULL COMMENT '主要作物，逗号分隔',
    `status`                  TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 停用',
    token_version             INT          NOT NULL DEFAULT 0 COMMENT '令牌版本号，重置密码 / 找回账号换绑 / 更换手机号 / 退出登录时加 1',
    last_login_at             DATETIME     NULL COMMENT '最近登录时间',
    sms_verified_at          DATETIME     NULL COMMENT '最近一次通过短信验证码证明手机号归属的时间（10 分钟内生成恢复码免再次验证）',
    created_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone),
    KEY idx_created (created_at),
    KEY idx_last_login (last_login_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户';

-- ---------------------------------------------------------------------
-- 短信验证码：6 位，5 分钟有效，用后作废；同手机号同场景 60 秒限频
-- REBIND（找回账号，发往新手机号）与 LOGIN / REGISTER / RESET 由 auth/sms/send 发送；
-- CHANGE_PHONE（更换手机号，发往新手机号）、DEACTIVATE（注销账号）、RECOVERY（生成恢复码，后两者都发往当前手机号）
-- 只能由需要登录的接口发送
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sms_code (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    phone       VARCHAR(11) NOT NULL,
    scene       VARCHAR(16) NOT NULL COMMENT 'LOGIN / REGISTER / RESET / REBIND / CHANGE_PHONE / DEACTIVATE / RECOVERY',
    code        CHAR(6)     NOT NULL,
    expire_at   DATETIME    NOT NULL COMMENT '发送时间 + 5 分钟',
    used        TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未使用 / 1 已使用',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_phone_scene_created (phone, scene, created_at),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '短信验证码';

-- ---------------------------------------------------------------------
-- 一次性凭证：找回密码的 reset_ticket 与找回账号的 rebind_ticket 共用，purpose 区分；
-- 只存 SHA-256 摘要，10 分钟有效，一次性
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_reset_ticket (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    phone        VARCHAR(11) NOT NULL COMMENT '生成凭证时的手机号（找回账号为原手机号）',
    ticket_hash  CHAR(64)    NOT NULL COMMENT '凭证明文的 SHA-256（十六进制）',
    purpose      VARCHAR(20) NOT NULL DEFAULT 'RESET' COMMENT 'RESET 找回密码 / REBIND_PASSWORD、REBIND_RECOVERY 找回账号（分别用登录密码、恢复码验证）',
    expire_at    DATETIME    NOT NULL COMMENT '生成时间 + 10 分钟',
    used         TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未使用 / 1 已使用或已作废',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_hash (ticket_hash),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '找回密码 / 找回账号一次性凭证';

-- ---------------------------------------------------------------------
-- 识别记录（逻辑删除，查询一律带 deleted = 0）
-- model / fallback（迁移 007）：实际给出结果的模型与是否由备用模型（DeepSeek）给出，只供后台查看；
-- model_name 是 App 显示的「由 xx 识别」，改用备用模型时不变
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_recognition (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    `mode`        VARCHAR(8)    NOT NULL COMMENT 'PEST / CROP',
    image_url     VARCHAR(255)  NOT NULL COMMENT '照片相对路径，如 rec/20261013/{uuid}.jpg',
    `status`      VARCHAR(16)   NOT NULL COMMENT 'SUCCESS / IMAGE_BAD / FAILED',
    top_name      VARCHAR(100)  NULL COMMENT '最可能结果',
    confidence    DECIMAL(4, 3) NULL COMMENT '匹配度 0–1',
    severity      VARCHAR(8)    NULL COMMENT '健康 / 轻度 / 中度 / 较重',
    category      VARCHAR(64)   NULL COMMENT '类别',
    part          VARCHAR(64)   NULL COMMENT '发病部位',
    spread_fast   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否传播快',
    evidence      TEXT          NULL COMMENT '识别依据',
    urgency_days  INT           NULL COMMENT '建议几天内处理',
    others_json   TEXT          NULL COMMENT '其他可能（CandidateVO 数组 JSON）',
    fail_reason   VARCHAR(16)   NULL COMMENT 'TIMEOUT / UNAVAILABLE / IMAGE_BAD',
    model_name    VARCHAR(64)   NULL COMMENT '识别模型显示名',
    model         VARCHAR(64)   NULL COMMENT '给出结果的模型名（如 qwen3.8-max、deepseek-flash，Mock 为 mock）；成功与照片不清晰时有值，失败与旧数据为空',
    fallback      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 由备用模型给出结果（主模型通义千问失败后改用 DeepSeek） / 0 否',
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 已删除',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_deleted_created (user_id, deleted, created_at),
    KEY idx_user_deleted_mode_created (user_id, deleted, `mode`, created_at),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '识别记录';

-- ---------------------------------------------------------------------
-- AI 对话会话：每写入一条消息都把 updated_at 更新为当前时间，历史对话按 (user_id, updated_at) 倒序
-- 标题：追问识别结果为「追问：病名」；普通对话为第一条提问的前 30 个字（带图且无文字为「图片提问」）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_ai_session (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    record_id   BIGINT      NULL COMMENT '追问识别结果时的识别记录 ID',
    title       VARCHAR(64) NULL COMMENT '会话标题',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_created (user_id, created_at),
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 对话会话';

-- ---------------------------------------------------------------------
-- AI 对话消息：回答完成或停止生成时，正文与思考过程（reasoning_content、reasoning_ms）一起保存；
-- 停止生成时正文还没开始则 content 为空字符串；只发图片的提问 content 也为空字符串
-- 带图提问（1–9 张）：image_url 存第一张（历史对话列表据此显示「[图片]」），全部图片见 t_ai_message_image
-- model / fallback（迁移 007）：AI 回答所用的模型与是否由备用模型（通义千问）回答，只供后台查看
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_ai_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  BIGINT       NOT NULL,
    `role`      VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    content     TEXT         NOT NULL COMMENT '用户问题 / 完整回答',
    image_url   VARCHAR(255) NULL COMMENT '带图提问时第一张图片的相对路径（全部图片见 t_ai_message_image）',
    reasoning_content MEDIUMTEXT NULL COMMENT '思考过程全文（DeepSeek reasoning_content；没有思考时为空）',
    reasoning_ms      INT        NULL COMMENT '从开始请求上游到收到第一个正文片段的毫秒数（只在有思考内容时记录）',
    model       VARCHAR(64)  NULL COMMENT '生成回答的模型名（如 deepseek-flash、qwen3.8-max，Mock 为 mock）；只有 assistant 消息有值，用户消息与旧数据为空',
    fallback    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 由备用模型回答（主模型 DeepSeek 失败后改用通义千问） / 0 否',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_created (session_id, created_at),
    KEY idx_role_created (`role`, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 对话消息';

-- ---------------------------------------------------------------------
-- AI 对话消息的图片：一条带图提问最多 9 张，sort_no 为用户选择的顺序（从 0 开始）；与提问在同一事务写入，之后不再修改
-- （因此只有 created_at）。删除对话 / 注销账号时先删图片行再删消息；未被任何消息引用的 chat/ 图片由定时任务清理
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_ai_message_image (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    message_id  BIGINT       NOT NULL COMMENT 't_ai_message.id（user 消息）',
    sort_no     INT          NOT NULL COMMENT '图片顺序，从 0 开始',
    image_url   VARCHAR(255) NOT NULL COMMENT '图片相对路径，如 chat/20261014/{uuid}.jpg',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_message (message_id, sort_no),
    KEY idx_image_url (image_url)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 对话消息的图片';

-- ---------------------------------------------------------------------
-- App 版本（预留，v1.0 App 不调用 app/version）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_app_version (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    version_code   INT           NOT NULL COMMENT '与 BuildConfig.VERSION_CODE 比较',
    version_name   VARCHAR(32)   NOT NULL,
    download_url   VARCHAR(255)  NOT NULL COMMENT '安装包相对路径（拼 {BASE_URL}/download/）或完整地址',
    force_update   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 强制更新',
    release_note   VARCHAR(1000) NULL COMMENT '更新说明',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_code (version_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'App 版本';

-- ---------------------------------------------------------------------
-- 后台管理员（迁移 006）：服务端启动时若表为空，自动创建 admin（密码取环境变量 PESTAI_ADMIN_INITIAL_PASSWORD，
-- 未设置时随机生成并在启动日志中打印一次），must_change_password = 1，首次登录后必须修改密码；
-- 同一账号连续输错 5 次锁定 15 分钟（locked_until），同一 IP 的失败次数只记在内存中
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 后台操作日志（迁移 006）：登录成功 / 失败、退出与全部写操作；写入后不再修改（只有 created_at）；
-- detail 中不写密钥明文与用户密码
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 系统配置（迁移 006）：后台设置的 API Key、模型名、短信签名与模板，key 与环境变量同名；
-- 生效顺序：本表 > 环境变量 / .env > 默认值，修改后立即生效。密钥类（secret = 1）用 AES-GCM 加密保存，
-- 密钥取环境变量 PESTAI_CONFIG_SECRET（未设置时由 JWT_SECRET 派生）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_system_config (
    config_key    VARCHAR(64)  NOT NULL COMMENT '与环境变量同名，如 DASHSCOPE_API_KEY、VISION_MODEL',
    config_value  TEXT         NOT NULL COMMENT '配置值；secret = 1 时为密文（v1:Base64(IV + 密文)）',
    secret        TINYINT      NOT NULL DEFAULT 0 COMMENT '1 密钥类（加密保存，后台只显示掩码）',
    updated_by    VARCHAR(32)  NULL COMMENT '最后修改的管理员',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统配置（后台设置，优先于环境变量）';
