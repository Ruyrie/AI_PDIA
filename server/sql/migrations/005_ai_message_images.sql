-- =====================================================================
-- 迁移 005：AI 问答多图提问（一条消息最多 9 张图片）—— 新增图片表 t_ai_message_image，并由 t_ai_message.image_url 回填
-- 依据：docs/AI问答多图提问.md「三、数据表」
-- 适用：按旧版 sql/schema.sql 建好的已有库（新建库直接执行最新的 schema.sql，已包含以下变更，无需本脚本）
-- 用法：mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/005_ai_message_images.sql
--       PowerShell：Get-Content sql/migrations/005_ai_message_images.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4 pestai
-- 说明：可重复执行 —— 建表用 CREATE TABLE IF NOT EXISTS；回填只插入「还没有任何图片行」的带图消息，
--       第二次执行不会重复插入；t_ai_message.image_url 只更新列注释。不修改、不删除任何已有数据。
--       已有的带图提问（单张）回填为 sort_no = 0 的一行，created_at 与消息相同；t_ai_message.image_url 保留（仍为第一张图片，
--       历史对话列表据此显示「[图片]」）。
--       请在部署新版本服务端之前执行（新版本保存带图提问时写入 t_ai_message_image，读取、删除对话、注销账号时也会查询它，
--       未迁移的库会让 AI 问答、历史对话、注销账号报错）。
-- =====================================================================

-- 数据库名不是 pestai 时改成实际库名
USE pestai;

-- 1. 新增 t_ai_message_image（与 schema.sql 一致）
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

-- 2. 回填：已有的带图提问（image_url 非空）各写入一行 sort_no = 0；已有图片行的消息跳过（可重复执行）
INSERT INTO t_ai_message_image (message_id, sort_no, image_url, created_at)
SELECT m.id, 0, m.image_url, m.created_at
FROM t_ai_message m
WHERE m.image_url IS NOT NULL
  AND m.image_url <> ''
  AND NOT EXISTS (SELECT 1 FROM t_ai_message_image i WHERE i.message_id = m.id);

-- 3. t_ai_message.image_url：含义改为「第一张图片」，只更新注释
ALTER TABLE t_ai_message
    MODIFY COLUMN image_url VARCHAR(255) NULL COMMENT '带图提问时第一张图片的相对路径（全部图片见 t_ai_message_image）';

-- 执行后可检查：
--   SHOW CREATE TABLE t_ai_message_image;
--   SELECT COUNT(*) FROM t_ai_message WHERE image_url IS NOT NULL AND image_url <> '';   -- 与下一条结果相同
--   SELECT COUNT(DISTINCT message_id) FROM t_ai_message_image;
