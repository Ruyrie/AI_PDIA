-- =====================================================================
-- AI 病虫害识别助手 · 数据库建表脚本（MySQL 8.0+，InnoDB，utf8mb4）
-- 依据：公共数据/04-后端接口与数据表.md「三、数据表」及各模块技术准备文档
-- 用法：mysql -u root -p < sql/schema.sql
--       新建库直接执行本文件即可；已按旧版本建好的库请执行 sql/migrations/ 下尚未执行过的迁移脚本
-- 说明：每张表都有 id（BIGINT 自增主键）、created_at、updated_at；
--       应用写库时由服务端按 Asia/Shanghai 填写时间，列默认值只作兜底。
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
    `identity`                VARCHAR(16)  NOT NULL DEFAULT '种植户' COMMENT '种植户 / 家庭园艺 / 农技人员',
    crops                     VARCHAR(255) NULL COMMENT '主要作物，逗号分隔',
    `status`                  TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 停用',
    token_version             INT          NOT NULL DEFAULT 0 COMMENT '令牌版本号，重置密码 / 找回账号换绑 / 更换手机号 / 退出登录时加 1',
    last_login_at             DATETIME     NULL COMMENT '最近登录时间',
    sms_verified_at          DATETIME     NULL COMMENT '最近一次通过短信验证码证明手机号归属的时间（10 分钟内生成恢复码免再次验证）',
    created_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
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
    KEY idx_phone_scene_created (phone, scene, created_at)
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
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 已删除',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_deleted_created (user_id, deleted, created_at),
    KEY idx_user_deleted_mode_created (user_id, deleted, `mode`, created_at)
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
-- 停止生成时正文还没开始则 content 为空字符串
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_ai_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  BIGINT       NOT NULL,
    `role`      VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    content     TEXT         NOT NULL COMMENT '用户问题 / 完整回答',
    image_url   VARCHAR(255) NULL COMMENT '带图提问时的图片相对路径',
    reasoning_content MEDIUMTEXT NULL COMMENT '思考过程全文（DeepSeek reasoning_content；没有思考时为空）',
    reasoning_ms      INT        NULL COMMENT '从开始请求上游到收到第一个正文片段的毫秒数（只在有思考内容时记录）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_created (session_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'AI 对话消息';

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
