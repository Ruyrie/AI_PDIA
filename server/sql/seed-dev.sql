-- =====================================================================
-- 仅限本机开发 / 联调：演示账号（与原型示例用户「老李种菜」一致）
-- 生产环境不要执行本文件。
-- 用法：mysql -u root -p pestai < sql/seed-dev.sql
--
-- 演示账号：手机号 13800005678，密码 Pest2026demo（BCrypt 哈希如下）
-- 也可以直接用验证码登录任意手机号：未配置阿里云短信时，验证码打印在服务器日志中。
-- =====================================================================

USE pestai;

INSERT INTO t_user (phone, password_hash, nickname, avatar_url, region, `identity`, crops, `status`, token_version,
                    created_at, updated_at)
VALUES ('13800005678', '$2a$10$/a3qDsdYuYOPGdCU499qTOvRBhK3gmBSR.gZTlDEBd.RYyVy9y57K', '老李种菜', NULL,
        '江苏省 南京市 江宁区', '种植户', '番茄,黄瓜,小麦', 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE updated_at = NOW();
