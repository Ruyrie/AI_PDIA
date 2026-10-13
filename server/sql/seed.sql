-- =====================================================================
-- AI 病虫害识别助手 · 初始数据（在 schema.sql 之后执行，可重复执行）
-- 用法：mysql -u root -p pestai < sql/seed.sql
--
-- 说明：
-- 1. 「大家常问」问题池按模块08 的设计放在配置文件 src/main/resources/suggestions.yml
--    （pestai.ai.suggestions，按春夏秋冬四季各一组），不建表；修改问题后重启服务即可。
-- 2. t_app_version 预留给 GET app/version（v1.0 App 不调用），这里写入当前版本 1.0.0。
--    download_url 为相对路径，接口返回时拼成 {PESTAI_PUBLIC_BASE_URL}/download/pestai-1.0.0.apk。
-- =====================================================================

USE pestai;

INSERT INTO t_app_version (version_code, version_name, download_url, force_update, release_note, created_at, updated_at)
VALUES (1, '1.0.0', 'pestai-1.0.0.apk', 0, '首个版本：拍照识别病虫害与作物 / 杂草、识别记录、AI 问答。', NOW(), NOW())
AS new_row
ON DUPLICATE KEY UPDATE
    version_name = new_row.version_name,
    download_url = new_row.download_url,
    force_update = new_row.force_update,
    release_note = new_row.release_note,
    updated_at   = NOW();
