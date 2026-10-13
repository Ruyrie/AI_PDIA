# pestai-server（AI 病虫害识别助手后端）

Spring Boot 3.3.5 + MyBatis 3.0.3 + MySQL 8.0 的 REST 后端，接口全部挂在 `/pestai/v1/` 下，图片静态资源在 `/files/` 下。
设计依据：《第六组_病虫害识别助手_UI设计与技术准备文档》中的 `公共数据/04-后端接口与数据表.md`、`05-公共数据模型与示例数据.md` 及模块 01–09 技术准备文档。

- 所有密钥与连接信息都来自**环境变量**，不写进代码与配置文件。
- **未配置大模型 Key 时自动使用 Mock 实现**，未配置阿里云短信时验证码只打印到日志 —— Key 配置前 App 也能完整联调。

## 1. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 及以上（本机用 JDK 21 运行） | 编译目标为 Java 17 |
| Maven | 3.9.x | 命令 `mvn` |
| MySQL | 8.0.36 及以上（9.x 也可） | InnoDB、utf8mb4 |

## 2. 建库建表

```bash
cd server
# 建库（pestai）+ 7 张表 + 索引
mysql -u root -p --default-character-set=utf8mb4 < sql/schema.sql
# 初始数据：t_app_version 写入 1.0.0（可重复执行）
mysql -u root -p --default-character-set=utf8mb4 pestai < sql/seed.sql
# 可选，仅本机开发：演示账号「老李种菜」（手机号与密码见文件注释），生产环境不要执行
mysql -u root -p --default-character-set=utf8mb4 pestai < sql/seed-dev.sql
```

PowerShell 不支持 `<` 重定向，可改用：`Get-Content sql/schema.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4`，或在 Navicat 中运行这几个 SQL 文件。

### 已有数据库升级（迁移脚本）

`schema.sql` 始终是最新的完整建表脚本，**新建库只需执行它**。按旧版本建好、已有数据的库，按编号顺序执行 `sql/migrations/` 下尚未执行过的脚本（**先迁移，再部署新版本服务端**）：

| 脚本 | 内容 |
|---|---|
| `001_appeal_new_password.sql` | 旧版换绑表 `t_appeal` 新增 `new_password_hash`（该表已由 003 删除，表不存在时自动跳过）；`t_sms_code.scene`、`t_user.token_version` 只更新注释 |
| `002_ai_reasoning_and_history.sql` | `t_ai_message` 新增 `reasoning_content`（MEDIUMTEXT，思考过程全文）、`reasoning_ms`（INT，思考耗时毫秒）；`t_ai_session` 新增索引 `idx_user_updated (user_id, updated_at)`（历史对话排序） |
| `003_recover_account.sql` | 找回账号与账号恢复码：**删除 `t_appeal` 表**；`t_user` 新增 `recovery_code_hash`（VARCHAR(100)，恢复码 BCrypt 哈希）、`recovery_code_created_at`（DATETIME）；`t_reset_ticket` 新增 `purpose`（VARCHAR(20)，默认 `RESET`）；`t_sms_code.scene`、`t_user.token_version` 与 `t_reset_ticket` 的注释更新 |
| `004_sms_verified_at.sql` | `t_user` 新增 `sms_verified_at`（DATETIME）：最近一次通过短信验证码证明手机号归属的时间；验证码登录 / 注册 / 更换手机号 / 找回账号换绑时写入，10 分钟内生成恢复码免再次短信验证 |

```bash
cd server
# 先备份：mysqldump -u root -p pestai > pestai-backup.sql
mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/001_appeal_new_password.sql
mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/002_ai_reasoning_and_history.sql
mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/003_recover_account.sql
```

PowerShell：`Get-Content sql/migrations/003_recover_account.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4 pestai`（001、002、004 同理，换成对应文件名）。

- **001**：只加可空列、改注释，不动已有数据，误执行两次也不会报错；`t_appeal` 已不存在时跳过加列。
- **002**：只加两个可空列和一个索引，不动已有数据；加列、加索引前先查 `information_schema`，误执行两次也不会报错。执行后可用 `SHOW FULL COLUMNS FROM t_ai_message WHERE Field IN ('reasoning_content', 'reasoning_ms');`、`SHOW INDEX FROM t_ai_session WHERE Key_name = 'idx_user_updated';` 确认。迁移前的消息没有思考内容（两列为空，App 不显示思考区）。**必须在部署新版本服务端之前执行**：新版本写入消息时会写这两列，未迁移的库会让每次保存回答失败。
- **003**：可重复执行（`DROP TABLE IF EXISTS`；加列前先查 `information_schema`，已存在则跳过）。除整表删除 `t_appeal`（其中的记录不再使用，需要留档请先单独备份该表）外不动已有数据：已有凭证的 `purpose` 取默认值 `RESET`（都是找回密码凭证），已有用户的恢复码为空（未生成）。执行后可用 `SHOW TABLES LIKE 't_appeal';`（应为空）、`SHOW FULL COLUMNS FROM t_user WHERE Field IN ('recovery_code_hash', 'recovery_code_created_at');`、`SHOW FULL COLUMNS FROM t_reset_ticket LIKE 'purpose';` 确认。**必须在部署新版本服务端之前执行**：新版本查询用户时会读这两列，未迁移的库会让登录、找回密码等接口全部报错。

| 表 | 用途 |
|---|---|
| t_user | 用户（phone 唯一；BCrypt 密码；token_version 用于令牌失效；recovery_code_hash / recovery_code_created_at 为账号恢复码的 BCrypt 哈希与生成时间，未生成或已用于找回账号后为空） |
| t_sms_code | 短信验证码（6 位、5 分钟有效、用后作废；scene 为 LOGIN / REGISTER / RESET / REBIND / CHANGE_PHONE / DEACTIVATE / RECOVERY） |
| t_reset_ticket | 一次性凭证（只存 SHA-256 摘要，10 分钟有效）：purpose 为 RESET（找回密码的 reset_ticket）或 REBIND_PASSWORD / REBIND_RECOVERY（找回账号的 rebind_ticket，分别表示用登录密码、恢复码验证） |
| t_recognition | 识别记录（逻辑删除 deleted） |
| t_ai_session | AI 对话会话（标题：追问识别结果为「追问：病名」，普通对话为第一条提问的前 30 个字、带图且无文字为「图片提问」；每写入一条消息都把 updated_at 更新为当前时间，历史对话按索引 `idx_user_updated (user_id, updated_at)` 倒序） |
| t_ai_message | AI 对话消息（content 为问题 / 回答正文；image_url 为带图提问的图片相对路径；reasoning_content 为回答的思考过程全文，reasoning_ms 为从开始请求上游到收到第一个正文片段的毫秒数，只在有思考内容时记录；回答完成或停止生成时与正文一起保存） |
| t_app_version | App 版本（预留，v1.0 App 不调用） |

「大家常问」问题池按模块 08 的设计放在配置文件 `src/main/resources/suggestions.yml`（按春夏秋冬四季各一组，优先挑选与用户主要作物相关的问题，每天轮换），修改后重启生效。

## 3. 环境变量

**本机开发推荐把变量写进 `server/.env`**：复制 `.env.example` 为 `.env` 后填写，启动时由 `application.yml` 自动读取（`spring.config.import`），无需每次手动设置；同名的系统环境变量优先。`.env` 已被 `.gitignore` 忽略，**不要提交到仓库**。Spring Boot 按 ISO-8859-1 读取该文件，中文要写成 `\uXXXX` 转义（如签名「恒创联众」写成 `\u6052\u521b\u8054\u4f17`）。

- `JWT_SECRET`：给登录令牌（JWT）签名的密钥，防止令牌被伪造；自己生成一串随机字符即可，例如 `python -c "import secrets; print(secrets.token_urlsafe(48))"`。更换后所有用户需要重新登录。

| 环境变量 | 必填 | 默认值 / 不配置时的行为 |
|---|---|---|
| `DB_URL` | 否 | `jdbc:mysql://localhost:3306/pestai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true` |
| `DB_USERNAME` | 否 | `root` |
| `DB_PASSWORD` | **是**（本机 root 有密码时） | 空 |
| `JWT_SECRET` | **生产必填** | 不配置时使用代码内置的开发密钥，启动日志告警；生产环境必须配置 ≥ 32 字节的随机串（不足 32 字节启动失败） |
| `DASHSCOPE_API_KEY` | 否 | 通义千问（北京地域，识图默认 `qwen3.8-max`）。**不配置时使用 MockVisionClient**：按图片哈希在几条示例结果（黄瓜霜霉病 0.92 中度、番茄早疫病、桃蚜、辣椒健康植株；作物模式：马唐、番茄、反枝苋）中轮换，同一张图结果稳定；约 5% 的照片模拟「照片不清晰」（42201，可用 `PESTAI_MOCK_IMAGE_BAD_PERCENT` 调整，0 为关闭）；模拟耗时 1.5 秒 |
| `DEEPSEEK_API_KEY` | 否 | DeepSeek（AI 问答，默认 `deepseek-flash`，文字与带图提问都由它回答）。**不配置时使用 MockChatClient**：先推送一小段模拟的思考过程（`event: reasoning`），再根据问题 / 识别结论生成一段固定中文回答，每 6 个字一段、间隔 40 毫秒流式推送 |
| `ALIYUN_SMS_ACCESS_KEY_ID` | 否 | 阿里云 AccessKey ID（建议使用只授予「号码认证服务」权限的 RAM 子账号） |
| `ALIYUN_SMS_ACCESS_KEY_SECRET` | 否 | 阿里云 AccessKey Secret |
| `ALIYUN_SMS_SIGN_NAME` | 否 | 号码认证服务 · 短信认证的赠送签名（如「恒创联众」） |
| `ALIYUN_SMS_TEMPLATE_CODE` | 否 | `100001` 登录 / 注册模板（也是兜底模板：某场景的模板变量配置为空时使用），变量 `${code}`、`${min}` |
| `ALIYUN_SMS_TEMPLATE_RESET` | 否 | `100003` 重置密码模板（找回密码） |
| `ALIYUN_SMS_TEMPLATE_REBIND` | 否 | `100004` 绑定新手机号模板（找回账号，场景 REBIND，验证码发往新手机号）。**原 `ALIYUN_SMS_TEMPLATE_APPEAL` 已改名为它**，旧名称不再读取 |
| `ALIYUN_SMS_TEMPLATE_CHANGE_PHONE` | 否 | `100002` 修改绑定手机号模板（更换手机号，验证码发往新手机号） |
| `ALIYUN_SMS_TEMPLATE_VERIFY` | 否 | `100005` 验证绑定手机号模板（注销账号 DEACTIVATE、生成恢复码 RECOVERY，验证码都发往当前手机号） |
| `SMS_PROVIDER` | 否 | `auto`（默认：AccessKey ID、Secret、签名齐全才用阿里云，否则验证码只打印到日志）/ `log` / `aliyun` |
| `VISION_MODEL` / `VISION_ENABLE_THINKING` | 否 | 识图模型，默认 `qwen3.8-max`（原生支持图片）/ 深度思考，默认 `false`（开启会明显变慢，可能超过 25 秒时限） |
| `CHAT_MODEL` / `CHAT_IMAGE_INPUT` | 否 | 问答模型，默认 `deepseek-flash`（DeepSeek 唯一支持图片的模型）/ 问答模型能否看图，默认 `true`。**换成纯文本模型（如 `deepseek-v4-pro`）时必须设为 `false`**，带图提问会自动改用识图模型，否则模型看不到图片却会照常作答 |
| `PESTAI_PUBLIC_BASE_URL` | 否 | `http://10.0.2.2:8080`（Android 模拟器访问本机）。图片地址 = 该值 + `/files/` + 相对路径 |
| `PESTAI_FILE_ROOT` | 否 | 不配置时：Windows 为 `./data/files`（相对启动目录），其他系统为 `/data/pestai/files` |
| `SERVER_PORT` | 否 | `8080` |
| `VISION_DISPLAY_NAME` | 否 | `通义千问`（识别结果的 model_name，界面显示「由 xx 识别」） |
| `PESTAI_MOCK_VISION_DELAY_MS` / `PESTAI_MOCK_IMAGE_BAD_PERCENT` / `PESTAI_MOCK_CHAT_CHUNK_DELAY_MS` | 否 | Mock 行为：识图耗时 1500 ms / 照片不清晰比例 5% / 流式片段间隔 40 ms |
| `PESTAI_DOWNLOAD_BASE_URL` | 否 | 安装包地址前缀（预留），默认 `PESTAI_PUBLIC_BASE_URL + /download/` |

启动日志会列出当前用的是真实服务还是 Mock / 日志实现（不会打印任何密钥）。

## 4. 本机运行

PowerShell：

```powershell
cd D:\Android_Program\AI_PDIA\server
# 已配置 server/.env 时直接运行即可；否则先设置 $env:DB_PASSWORD 等环境变量
mvn spring-boot:run
```

Git Bash / Linux：

```bash
cd server
mvn spring-boot:run
# 或打包后运行（在 server/ 或仓库根目录启动都会读取 server/.env）
mvn -DskipTests package && java -jar target/pestai-server.jar
```

测试：`mvn test`（JUnit 5 + Mockito + MockMvc，不连接数据库、不调用任何外部 API）。

### App 访问地址

| 场景 | App 的 BASE_URL（local.properties） | 服务器 `PESTAI_PUBLIC_BASE_URL` |
|---|---|---|
| Android 模拟器 | `http://10.0.2.2:8080` | 默认值即可 |
| 局域网真机 | `http://<电脑局域网 IP>:8080` | 改成同一地址（否则图片地址指向 10.0.2.2，真机加载不到图片）；Windows 防火墙需放行 8080 |

Retrofit 的 baseUrl 为 `{BASE_URL}/pestai/v1/`。本机开发没有 Nginx，图片由 Spring Boot 直接从文件根目录提供（`/files/**`）。App 使用 http 明文访问时需要 network_security_config 允许对应地址。

## 5. 接口一览（相对 `/pestai/v1/`）

| 方法 | 路径 | 登录 | 说明 |
|---|---|---|---|
| POST | auth/sms/send | 否 | phone、scene（只接受 LOGIN / REGISTER / RESET / REBIND，CHANGE_PHONE、DEACTIVATE、RECOVERY 返回 40001）；同手机号同场景 60 秒限频 42901；RESET 未注册 40104；REGISTER / REBIND 已注册 40901（REBIND 的 phone 为找回账号的新手机号） |
| POST | auth/login/password | 否 | LoginVO；40104 / 40102 / 40301 |
| POST | auth/login/sms | 否 | LoginVO；未注册自动注册（is_new = true，昵称「农友」+ 尾号） |
| POST | auth/register | 否 | LoginVO；密码 8–20 位且含字母和数字 |
| POST | auth/password/verify | 否 | ResetTicketVO（reset_ticket 32 位十六进制，expire_in 600） |
| POST | auth/password/reset | 否 | phone、reset_ticket、new_password；token_version + 1 |
| POST | auth/recover/verify | 否 | 找回账号第 1 步：`{"old_phone","method":"PASSWORD"\|"RECOVERY_CODE","password"?,"recovery_code"?}`；RebindTicketVO（rebind_ticket 32 位、expire_in 600、method）；40104 未注册；40102「账号或密码错误」；40105「恢复码错误或已失效」（未生成也返回它）；40301；40001 参数，见下 |
| POST | auth/recover/rebind | 否 | 找回账号第 2 步：`{"old_phone","rebind_ticket","new_phone","code","new_password"?}`（code 为新手机号收到的 REBIND 验证码）；LoginVO（新 token、is_new = false），本机直接登录、其他设备失效；40001（凭证无效 / 过期 / 已用「验证已过期，请重新验证身份」；新号与原号相同；恢复码方式缺少合规 new_password）；40103；40901 |
| POST | auth/logout | 是 | token_version + 1，该账号所有令牌失效 |
| GET | user/me | 是 | UserVO |
| PUT | user/profile | 是 | nickname、avatar_url（可空，必须是 user/avatar 返回的地址）、region、identity、crops |
| POST | user/avatar | 是 | multipart 字段 `avatar`；UploadVO |
| GET | user/stats | 是 | UserStatsVO |
| POST | user/phone/sms | 是 | `{"new_phone":"13900001111"}`；向新手机号发送 CHANGE_PHONE 验证码；格式不对或与当前手机号相同 40001（「新手机号不能与当前手机号相同」）；已注册 40901（「该手机号已注册，请先注销该账号或更换其他号码」）；42901 |
| PUT | user/phone | 是 | `{"new_phone":"…","code":"123456"}`；核销验证码（40103）后换绑、token_version + 1，返回 LoginVO（按新版本号签发的 token、更新后的 user、is_new = false）；其他设备的令牌失效；校验通过后被他人抢注 40901 |
| POST | user/deactivate/sms | 是 | 请求体 `{}`（可省略）；验证码（DEACTIVATE）发往当前账号手机号；42901 |
| POST | user/deactivate | 是 | `{"code":"123456"}`；核销验证码（40103）后立即注销，见下 |
| GET | user/recovery-code | 是 | RecoveryCodeVO：generated（是否已生成）、created_at（生成时间，未生成为 null）、recovery_code（恒为 null）、verify_required（生成时是否需要短信验证码；10 分钟内刚通过短信验证手机号时为 false） |
| POST | user/recovery-code/sms | 是 | 请求体 `{}`（可省略）；验证码（RECOVERY）发往当前账号手机号；42901 |
| POST | user/recovery-code | 是 | `{"code":"123456"}`，code 可省略（仅当 10 分钟内刚通过短信验证手机号，否则 40001「请先验证当前手机号」）；带 code 时核销验证码（40103）后生成 / 重新生成恢复码，RecoveryCodeVO（generated = true、created_at、**recovery_code 明文如 `ABCD-EFGH-JKLM`，只在这一次返回**）；旧恢复码立即失效 |
| GET | recognitions/recent?size=3 | 是 | RecordItemVO 数组（size 1–10） |
| POST | recognitions | 是 | multipart 字段 `image` + `mode`（PEST / CROP）；RecognitionVO；42201 / 50201 / 50401（失败记录也会入库） |
| GET | recognitions?mode=&page=1&size=20 | 是 | PageVO（size 1–50） |
| GET | recognitions/{id} | 是 | RecognitionVO |
| POST | recognitions/{id}/retry | 是 | 用原照片重新识别 |
| DELETE | recognitions/{id} | 是 | 逻辑删除（幂等） |
| GET | ai/suggestions | 是 | SuggestionVO 数组（3 条） |
| POST | ai/sessions | 是 | 请求体 `{"record_id": 2036}` 或 `{}`；SessionVO |
| POST | ai/sessions/{id}/messages | 是 | content（1–500 字）、image_url（可空）、regenerate（可空）；流式返回，见下；继续历史对话也用它（上下文取最近 10 轮） |
| GET | ai/sessions?page=1&size=20 | 是 | 历史对话：PageVO&lt;SessionItemVO&gt;（size 1–50，page ≥ 1，超出 40001）；只返回至少有 1 条消息的会话，按 updated_at 倒序 |
| GET | ai/sessions/{id} | 是 | 打开历史对话：SessionDetailVO；不是自己的会话 / 不存在 40001（「会话不存在」） |
| DELETE | ai/sessions/{id} | 是 | 删除会话与全部消息，data 为 null；幂等（不存在直接成功）；不是自己的会话 40001（「无权删除该对话」） |
| POST | files/image | 是 | multipart 字段 `image`；聊天图片 UploadVO |
| GET | app/version?version_code= | 否 | AppVersionVO（预留，v1.0 不调用） |

统一响应 `{"code":0,"message":"ok","data":…}`；业务错误 HTTP 状态码也是 200，以 code 区分（错误码见 04 文档）；JSON 字段为下划线命名，时间格式 `yyyy-MM-dd HH:mm:ss`（Asia/Shanghai）。

### 更换手机号与注销账号

需求见 `docs/新增功能-更换手机号与注销账号.md`。两类验证码只能通过上表需要登录的接口发送，用户只取自令牌：CHANGE_PHONE 发往新手机号（不验证原手机号），DEACTIVATE 发往当前账号手机号，不能借此向任意号码发码。

- **更换手机号**：成功后 `t_user.phone` 改为新号、`token_version + 1`，本机用返回的新 token 保持登录，其他设备的旧令牌返回 40101。
- **注销账号**：在一个事务内依次删除该用户的 `t_ai_message` → `t_ai_session` → `t_recognition`（含已逻辑删除的）→ `t_reset_ticket` → 该手机号的 `t_sms_code` → `t_user`（恢复码随之删除）；事务提交后尽力删除磁盘上的头像、识别照片、聊天图片（失败只记日志）。之后旧令牌因用户不存在返回 40101，该手机号可立即重新注册（全新账号）。上传后未保存到资料 / 消息中的图片不在数据表中，不会被删除。

### 找回账号与账号恢复码

需求见 `docs/找回账号与恢复码.md`。原手机号已停用（收不到验证码）时，只用**本人才知道的信息**自助验证身份，验证通过后验证新手机号、立即换绑并登录；全程自助完成，不收集昵称、登录时间、地区等无法核实的信息。

**找回账号**（两个接口都在白名单中，不需要登录）：

1. `auth/recover/verify`：原手机号 + 验证方式。`PASSWORD` 校验登录密码（验证码注册、从未设置密码的账号同样返回 40102）；`RECOVERY_CODE` 校验恢复码（忽略大小写、空格和连字符；未生成、已失效、格式不对都返回 40105）。method 与对应字段不配套时 40001（「请输入登录密码」/「请输入恢复码」，method 缺失为「请选择验证方式」）。通过后作废该用户此前未使用的凭证，签发 `rebind_ticket`（32 位随机串，库中只存 SHA-256 摘要，10 分钟有效，一次性，绑定到原手机号对应的用户）。这一步不发短信、不改账号，恢复码也不失效。
2. App 用 `auth/sms/send`（scene = REBIND）向**新手机号**发送验证码；新手机号已注册返回 40901。
3. `auth/recover/rebind`：在一个事务内依次校验凭证（存在、未用、未过期、用途是找回账号、属于该原手机号，且签发后账号没有换绑 / 注销、恢复码方式的恢复码仍有效）→ 新密码要求（`RECOVERY_CODE` 方式**必须**带 8–20 位且含字母和数字的 `new_password`；`PASSWORD` 方式可选，带了就更新）→ 新号 ≠ 原号且未注册 → 核销新号的 REBIND 验证码 → 更新手机号（恢复码方式同时更新密码并**清空恢复码**）、`token_version + 1`、更新 `last_login_at` → 凭证置为已用 → 按新 token_version 签发令牌。验证码之前的检查失败时验证码和凭证都不被消耗；之后任一步失败整体回滚。

**账号恢复码**（需要登录）：12 位，字符集 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`（去掉易混的 0 / O / 1 / I），用 `SecureRandom` 生成，返回时按 4 位分组显示为 `ABCD-EFGH-JKLM`；库中只存 BCrypt 哈希，明文只在 `POST user/recovery-code` 的响应中出现一次。生成 / 重新生成要先验证当前手机号（`user/recovery-code/sms` 发送 RECOVERY 验证码）；但验证码登录、注册、更换手机号、找回账号换绑后 **10 分钟内**可直接生成（`t_user.sms_verified_at`），避免阿里云同一手机号 60 秒只发 1 条导致「操作太频繁」；重新生成后旧恢复码立即失效，用于找回账号后恢复码也立即失效（需要重新生成）。

**凭证互不通用**：找回密码的 `reset_ticket`（purpose = RESET）不能用于换绑，找回账号的 `rebind_ticket` 也不能用于 `auth/password/reset`。以下操作会作废该用户所有未使用的凭证：签发新凭证（找回密码 / 找回账号第 1 步）、找回密码重置成功、生成 / 重新生成恢复码 —— 避免旧密码或旧恢复码泄露后，他人用此前拿到的凭证继续换绑。

密码、恢复码错误次数不做限制（产品负责人决定）；恢复码有 60 位熵，在线穷举不可行。密码和恢复码都不记得时无法找回：其他设备仍登录着时可在「设置 → 手机号」直接更换（`user/phone`），或用新手机号注册新账号。

### 流式回答格式

需求见 `docs/AI问答交互优化.md`「二、流式协议扩展」。成功时 `Content-Type: text/event-stream;charset=UTF-8`：

| 内容 | 格式 | 说明 |
|---|---|---|
| 思考片段 | `event: reasoning` 一行 + `data: 片段` 一行 + 空行 | DeepSeek 返回的 reasoning_content，在正文开始前出现；片段内换行与正文一样转义为 `\n` |
| 正文片段 | `data: 片段` + 空行（不带 event 行） | 片段内换行转义为 `\n` |
| 结束 | `data: [DONE]` | 回答（正文 + 思考过程 + 思考耗时）已写入 `t_ai_message` 之后才发出 |
| 出错 | `data: [ERROR]{"code":50201,"message":"AI 服务暂不可用，请稍后再试"}` | 生成途中出错，发出后关闭 |
| 保活 | 注释行 `:thinking` + 空行 | 超过 5 秒没有写出任何内容时发送（如带图提问改用识图模型时没有思考片段、正文开始前），App 忽略 |

示例：

```
event: reasoning
data: 用户问的是「蚜虫怎么防治？」。

event: reasoning
data: \n先判断属于病害、虫害还是管理问题……

data: 关于「蚜虫怎么防治？」，可以按下面的思路处理。\n

data: 处理建议

data: [DONE]

```

App 解析：遇到 `event: reasoning` 时，紧随其后的 data 行是思考片段；其他 data 行按原规则处理（正文 / [DONE] / [ERROR]）。带图提问改用识图模型（问答模型不能看图，`CHAT_IMAGE_INPUT=false`）时没有思考片段。

流开始前的错误（会话不存在、参数错误、42901 限流等）以普通 JSON 返回。每个用户每分钟最多提问 10 次（重新生成也计数）。客户端断开（停止生成）时服务端取消上游请求，并把已生成的思考与正文一起保存（正文还没开始时 content 为空字符串、reasoning_ms 为空）；`regenerate: true` 会删除上一条回答后重新生成。生成途中会话被删除时不再保存回答。

### 历史对话

- **列表** `GET ai/sessions`：`last_message` 为最后一条有文字或图片的消息去掉 Markdown 符号（加粗、标题、引用、无序列表符号、代码、链接等，编号「1.」保留）后的纯文字，换行合并为空格，最多 60 字；图片消息以「[图片]」开头（计入 60 字）。`title` 为空（会话还没有提问）时返回「新对话」；`updated_at` 为最后一条消息时间。
- **打开** `GET ai/sessions/{id}`：`record` 为关联识别记录的摘要（RecordItemVO，记录不存在或已删除时为 null）；`messages` 为全部消息（时间正序），图片为完整地址，AI 回答带 `reasoning_content`、`reasoning_ms`。
- **删除** `DELETE ai/sessions/{id}`：一个事务内删除消息与会话；事务提交后尽力删除该会话的聊天图片（仍被其他消息引用的图片保留，失败只记日志）。

## 6. 用 Apifox 调试

1. 新建环境，前置 URL 填 `http://localhost:8080/pestai/v1`。
2. `POST auth/sms/send`，Body 选 JSON：`{"phone":"13900001111","scene":"LOGIN"}`；验证码在服务器控制台日志中（`【开发短信】手机号 …，场景 LOGIN，验证码 xxxxxx`）。
3. `POST auth/login/sms` 得到 `data.token`，在环境变量中保存为 `token`，其余接口加请求头 `Authorization: Bearer {{token}}`（可在后置操作里自动提取）。
4. 上传接口 Body 选 form-data：识别为 `image`（文件）+ `mode`（文本），头像为 `avatar`，聊天图片为 `image`；只接受 jpg / png，≤ 2MB。
5. 流式接口请求头加 `Accept: text/event-stream`，Apifox 会按 SSE 逐条显示。

## 7. 部署提示（生产）

- 先执行尚未执行过的迁移脚本（见第 2 节，003 必须在新版本服务端部署之前执行）。
- 配置 `JWT_SECRET`、`DB_*`、各 Key，`PESTAI_PUBLIC_BASE_URL` 设为 `https://<域名>`，`PESTAI_FILE_ROOT=/data/pestai/files`；短信模板变量使用新名称 `ALIYUN_SMS_TEMPLATE_REBIND`。
- Nginx：`/pestai/v1/` 反向代理到 8080，`client_max_body_size 3m`；`/files/` 映射到 `/data/pestai/files/`；流式接口 `location ~ /pestai/v1/ai/sessions/.*/messages` 设 `proxy_buffering off; gzip off; proxy_read_timeout 180s;`。

## 8. 工程结构

```
server/
├── pom.xml
├── sql/                    schema.sql、seed.sql、seed-dev.sql、migrations/（已有库的升级脚本）
└── src/main/java/com/heban/pestai/server/
    ├── controller/         Auth、Sms、User、Recognition、Ai、File、AppVersion
    ├── service/            业务规则、事务、VO 组装（含 AccountService 更换手机号 / 注销账号、RecoverService 找回账号、
    │                       RecoveryCodeService / RecoveryCodes 账号恢复码、VisionResultParser、PromptBuilder、AiRateLimiter、
    │                       AiChatService 流式问答、AiSessionService 会话与历史对话、MessagePreview 列表预览）
    ├── mapper/             MyBatis Mapper 接口（SQL 在 resources/mapper/*.xml）
    ├── entity/  vo/  request/
    ├── common/             ApiResponse、BizException、ErrorCode、GlobalExceptionHandler、常量
    ├── security/           JwtTokenProvider、JwtInterceptor、UserContext
    ├── client/             VisionClient（通义千问 / Mock）、ChatClient（DeepSeek / Mock）、SmsClient（日志 / 阿里云）
    └── config/             WebMvcConfig、JacksonConfig、ClientConfig、各 Properties
```
