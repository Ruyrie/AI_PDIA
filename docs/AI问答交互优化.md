# AI 问答交互优化（Q01 / Q02 / 新增 Q03 历史对话）

| 项目 | 内容 |
|---|---|
| 所属项目 | AI 病虫害识别助手 |
| 来源 | 真机试用后的优化需求（已和产品负责人确认方案） |
| 涉及组件 | module_ai（Q01、Q02、新增 Q03）、lib_common（VO）、server（流式协议、接口、数据表） |
| 视觉规范 | 沿用《公共数据 · UI 设计规范》：Material 3、只引用主题属性、图标 Material Symbols Rounded、所有可点元素有水波纹 |

## 一、确认的方案

| 问题 | 方案 |
|---|---|
| Q01 右上角按钮点击无反应 | 改为「历史对话」入口（history 图标），打开新增的 Q03 历史对话页；Q02 右上角保持「新对话」 |
| 等待回复不美观 | 「正在思考… 已用 N 秒」+ 实时显示**可展开的思考过程**；正文开始输出后思考区自动折叠为「已思考 N 秒」，可点开查看 |
| 文字突然挤上去 | **平滑逐字输出 + 新字淡入**；列表平滑跟随，用户上滑查看时不强制滚动并出现「回到底部」按钮 |
| 三张能力卡片点击无效果 | 各自直达：文字提问 → 键盘 + 提问模板；带图提问 → 拍照 / 相册；追问结果 → 选择最近识别记录；卡片有按压反馈 |

## 二、流式协议扩展（POST ai/sessions/{id}/messages，text/event-stream）

| 内容 | 格式 | 说明 |
|---|---|---|
| 思考片段 | `event: reasoning` 一行 + `data: 片段` 一行 + 空行 | 新增。DeepSeek 返回的 reasoning_content，正文开始前出现；片段内换行与正文一样转义为 `\n` |
| 正文片段 | `data: 片段` + 空行（不带 event 行） | 不变 |
| 结束 / 出错 | `data: [DONE]`、`data: [ERROR]` + 错误 JSON | 不变 |
| 保活 | `: thinking` 注释行 | 不变；App 忽略 |

- 带图提问改用识图模型（问答模型不能看图时）没有思考片段，App 只显示「正在思考… 已用 N 秒」直到正文开始。
- App 解析：遇到 `event: reasoning` 时，紧随其后的 data 行是思考片段；其他 data 行按原规则处理（正文 / [DONE] / [ERROR]）。

## 三、数据与接口（相对 `{BASE_URL}/pestai/v1/`，JSON 下划线命名，都需要登录）

### 3.1 数据表（迁移脚本 `server/sql/migrations/002_ai_reasoning_and_history.sql`）

| 表 | 变更 |
|---|---|
| t_ai_message | 新增 `reasoning_content MEDIUMTEXT NULL`（思考过程全文）、`reasoning_ms INT NULL`（从开始请求上游到收到第一个正文片段的毫秒数，只在有思考内容时记录）；回答完成或停止生成时与正文一起保存 |
| t_ai_session | 新增索引 `(user_id, updated_at)`；每写入一条消息都把会话 `updated_at` 更新为当前时间（用于历史列表排序） |

会话标题规则：追问识别结果为「追问：病名」（现有逻辑）；普通对话在**第一条提问写入时**若标题为空，取问题文字前 30 个字（带图且无文字时为「图片提问」）。

### 3.2 VO（lib_common 与后端字段一致）

**ChatMessageVO** 新增：`reasoning_content`（string，可空）、`reasoning_ms`（integer，可空）。

**SessionItemVO（历史对话列表项）**

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| session_id | long | 否 | 会话 ID |
| record_id | long | 是 | 关联的识别记录（追问识别结果） |
| title | string | 否 | 会话标题（空时后端返回「新对话」） |
| last_message | string | 是 | 最后一条消息的纯文字预览，最多 60 字（去掉 Markdown 符号；图片消息为「[图片]」+ 文字） |
| message_count | integer | 否 | 消息条数 |
| updated_at | datetime | 否 | 最后一条消息时间 |

**SessionDetailVO（打开历史对话）**

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| session_id | long | 否 | |
| record_id | long | 是 | |
| title | string | 否 | |
| record | RecordItemVO | 是 | 关联记录的摘要（记录已删除时为空，App 不显示引用卡） |
| messages | ChatMessageVO[] | 否 | 全部消息，按时间正序 |

### 3.3 接口

| 方法 | 路径 | 请求 | 返回 data | 说明 |
|---|---|---|---|---|
| GET | ai/sessions | page（默认 1）、size（默认 20，1–50） | PageVO&lt;SessionItemVO&gt; | 只返回至少有 1 条消息的会话，按 updated_at 倒序 |
| GET | ai/sessions/{id} | — | SessionDetailVO | 不是自己的会话返回 40001 |
| DELETE | ai/sessions/{id} | — | null | 删除会话与全部消息，事务提交后尽力删除聊天图片文件；幂等；不是自己的会话返回 40001 |
| POST | ai/sessions/{id}/messages | 不变 | 流式 | 继续历史对话直接用原接口（上下文取最近 10 轮） |

## 四、App 页面与交互（module_ai）

### Q01 AI 问答（改动）

> 2026-10 起 Q01 精简为新对话页，下表为当前设计；原三张能力卡片、提问模板、大家常问列表、最近识别卡与「选择要追问的识别记录」面板已删除。

| 元素 | 交互 |
|---|---|
| 顶部栏 | 左侧标题「AI 问答」（TitleLarge），右侧 history 图标「历史对话」，打开 Q03；不再显示「大模型生成 · 仅供参考」标签 |
| 欢迎语 | 顶部栏与输入栏之间的空白区居中：48dp primaryContainer 圆角图标块（auto_awesome 实心图标）+ 一行「有什么种植问题？」（TitleLarge），没有说明段落；键盘弹出或出现缩略图条时随空白区重新居中，空间不够时隐藏，不与输入栏重叠 |
| 输入栏 | 与 Q02 共用的胶囊输入栏（见 docs/AI问答多图提问.md 5.1）；下方居中一行「内容由大模型生成，仅供参考」（LabelSmall，onSurfaceVariant） |
| 游客 | 点输入框、拍照、「+」或历史对话时弹出「登录后才能使用」 |

追问识别结果从 R01「追问 AI」进入（RouterPath.AI_CHAT 带 record_id），Q01 不再提供入口；App 不再请求 `GET ai/suggestions`（接口保留）。

### Q02 对话页（改动）

**思考区（AI 气泡顶部）**

| 状态 | 显示 |
|---|---|
| 已发送、尚无任何片段 / 思考中 | 一行：旋转的 auto_awesome 小图标（16dp）+「正在思考…」（文字流光渐变动画）+ 右侧「已用 8 秒」（BodySmall，每秒刷新，从发送时刻计时）；下方思考内容（BodySmall、onSurfaceVariant、左侧 2dp outlineVariant 竖线、默认展开、平滑逐字输出） |
| 正文开始输出 | 思考区自动折叠成一行「已思考 12 秒」+ expand_more 图标；点击展开 / 收起（高度动画 200ms），展开后 expand_less |
| 没有思考内容（带图提问走识图模型） | 只显示「正在思考… 已用 N 秒」，正文开始后整行隐藏 |
| 停止生成 | 思考区保留已生成内容并折叠，标题为「已停止思考」（若正文未开始）或「已思考 N 秒」 |
| 历史消息 | 有 reasoning_content 时显示折叠的「已思考 N 秒」（N 取 reasoning_ms 四舍五入到秒，至少 1） |

**平滑逐字 + 新字淡入**

- 网络片段先进缓冲区，按帧（Choreographer）逐字显示：基础速度约 35 字 / 秒，积压较多时自动加速，保证积压在约 0.6 秒内追平；收到 [DONE] 后 0.3 秒内显示完剩余内容。
- 最新显示的约 8 个字带透明度渐变（新字从透明淡入，约 160ms），替代现在的竖条光标。
- Markdown 渲染（加粗、小标题、编号列表）作用在已显示的文字上；刷新频率不超过 30 次 / 秒，只局部刷新当前气泡。
- 思考内容同样逐字显示（速度可更快）。

**滚动**

- 列表在底部（距离底部 48dp 以内）时，内容增长平滑跟随，不跳动；用户上滑后停止跟随。
- 不在底部时，在输入栏上方右侧显示 40dp 圆形「回到底部」按钮（arrow_downward，surfaceContainerHigh 底、level2 阴影）；点击平滑滚到底部并恢复跟随。

**打开历史对话**：Q02 支持 Intent 参数 `session_id`（module_ai 内部常量）。先显示加载中，调用 GET ai/sessions/{id} 渲染全部消息（有记录时顶部显示引用卡），之后继续提问直接追加到该会话。加载失败显示错误与「重试」。

### Q03 历史对话（新增，module_ai）

| 区域 | 内容 |
|---|---|
| 顶部栏 | 返回 + 标题「历史对话」 |
| 分组 | 按 updated_at 分为「今天」「昨天」「近 7 天」「更早」，分组标题 TitleSmall primary 色 |
| 列表项 | 72dp 双行：标题 TitleMedium 单行省略；第二行最后一条消息预览 BodyMedium 单行省略；右上角时间 BodySmall（今天 HH:mm、昨天、MM月dd日）；关联识别记录的会话在标题后加小标签「识别追问」（tertiaryContainer）；分组圆角卡片样式与其他列表一致 |
| 点击 | 打开 Q02 继续该对话 |
| 长按 | 弹出确认对话框「删除这条对话？」/「删除后无法恢复。」/「取消」「删除」（error 色）；删除成功后移除并 Snackbar「已删除」 |
| 其他 | 下拉刷新、滑到底部自动加载下一页（每页 20）；从 Q02 返回时静默刷新 |
| 空状态 | 120dp 圆形 surfaceContainerHigh 底 + forum 图标，「还没有对话记录」，「在 AI 问答里提问后，对话会保存在这里」，按钮「开始提问」（返回 Q01） |
| 错误 | 「加载失败」+ 原因 +「重试」 |

## 五、模型自动备用（服务端，App 无改动）

问答主模型 DeepSeek（CHAT_MODEL）出故障时，服务端自动改用通义千问（CHAT_FALLBACK_MODEL，默认 qwen3.8-max，同样能看图）回答，
App 不提示、流式协议不变；后台在每条回答上记录所用模型。完整规则见 `docs/后台管理接口.md`「八、模型自动备用」及其实现说明。

| 项 | 规则 |
|---|---|
| 何时切换 | 只在 DeepSeek **还没有送出任何思考 / 正文片段**时失败才切换：HTTP 错误、连接失败、流没有以 `[DONE]` 结束，或开始请求后 20 秒内没有任何片段（取消 DeepSeek 的请求） |
| 已经开始输出 | 不切换，保持原行为：`[ERROR]`，App 显示「回答中断」 |
| 思考区 | 通义千问开启深度思考（`enable_thinking: true`），思考内容同样以 `event: reasoning` 推送，「正在思考… / 已思考 N 秒」照常显示；思考耗时从开始请求 DeepSeek 算起（含切换前等待的时间） |
| 熔断 | 切换后 60 秒内的提问直接交给通义千问，之后再试 DeepSeek |
| 不切换 | 后台关闭了自动备用（AI_FALLBACK_ENABLED=false）、没有配置 DASHSCOPE_API_KEY、DeepSeek 为 Mock（没有配置 DEEPSEEK_API_KEY）、用户已停止生成 |
| 带图提问 | CHAT_IMAGE_INPUT=true（默认）时与文字提问相同；为 false 时仍改交识图模型，不经过自动备用 |
| 记录 | `t_ai_message.model`（回答所用模型名）、`fallback`（是否由备用模型回答），迁移 `server/sql/migrations/007_model_fallback.sql`；只在后台显示，App 的 VO 不变 |
