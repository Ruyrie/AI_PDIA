# AI 问答多图提问（一条消息最多 9 张图片）

| 项目 | 内容 |
|---|---|
| 所属项目 | AI 病虫害识别助手 |
| 来源 | 真机试用后的需求：一次只能带 1 张图，拍多个角度 / 多株作物时要分几次问 |
| 涉及组件 | server（接口、数据表、定时清理，已完成）、lib_common / lib_network（VO、请求体）、module_ai（Q02 输入区与消息气泡） |
| 兼容 | 旧版 App 的单图字段 `image_url` 继续可用；旧数据由迁移脚本回填，历史对话照常显示 |

## 一、需求与交互概述

| 项 | 规则 |
|---|---|
| 数量 | 一条消息最多 **9 张**图片；服务端同样校验，超过返回 40001「最多上传 9 张图片」 |
| 选图即上传 | 拍照或从相册选图后立即调用 `POST files/image` 上传（一次一张），输入区显示待发送图片的缩略图；发送时只提交已上传成功的图片地址 |
| ✕ 删除 | 每张缩略图上的 ✕ 把它从本条消息中移除；已上传的文件不需要 App 删除，服务器定期清理（见第四节） |
| 可只发图片 | 有图片时文字可以为空，直接发送；没有图片时文字不能为空（「请输入问题」）。文字最多 500 字 |
| 对话里宫格显示 | 用户消息气泡中的图片按选择顺序以宫格排列；只发图片时气泡里只有图片 |
| 点击全屏预览 | 点任一张图片全屏预览，可左右切换查看本条消息的全部图片 |
| 模型如何看图 | 本轮的全部图片按顺序一起发给模型（问答模型不能看图时改用识图模型，同样收到全部图片）；之前轮次的图片不再重复发送，只在上下文中注明「用户附带了 N 张图片」 |
| 标题与列表 | 普通对话第一条提问只有图片（或文字是旧版默认问题）时，会话标题为「图片提问」；历史对话列表中带图消息的预览仍以「[图片]」开头，只发图片时预览就是「[图片]」 |

## 二、接口变更（相对 `{BASE_URL}/pestai/v1/`，JSON 下划线命名，都需要登录）

### 2.1 发送问题 `POST ai/sessions/{id}/messages`（流式返回格式不变）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| content | string | 否 | 问题文字，最多 500 字；**有图片时可以为空字符串**；没有图片时不能为空 |
| image_urls | string[] | 否 | 新增。0–9 项，每项为 `POST files/image`（聊天图片）返回的完整地址，按用户选择的顺序；重复的地址只算一次 |
| image_url | string | 否 | 旧版单图字段，仍然接受：`image_urls` 为空时，非空的 `image_url` 视为 `[image_url]`；`image_urls` 非空时忽略它 |
| regenerate | boolean | 否 | 不变。重新生成时，文字与图片（地址及顺序）都与上一条问题相同才复用该问题 |

请求示例（只发 3 张图片）：

```json
{
  "content": "",
  "image_urls": [
    "http://10.0.2.2:8080/files/chat/20261014/3f2b9c0e8a7d4f6e9b1c2d3e4f5a6b7c.jpg",
    "http://10.0.2.2:8080/files/chat/20261014/7a1d2c3b4e5f60718293a4b5c6d7e8f9.jpg",
    "http://10.0.2.2:8080/files/chat/20261014/c0ffee00112233445566778899aabbcc.png"
  ],
  "regenerate": false
}
```

旧版 App（仍然可用）：`{"content":"叶子背面的黑点是什么","image_url":"http://10.0.2.2:8080/files/chat/20261014/3f2b….jpg"}`

流开始前的错误以 JSON 返回（HTTP 200，`Content-Type: application/json`），code 都是 40001：

| 情况 | message |
|---|---|
| 没有图片且文字为空（或只有空白） | 请输入问题 |
| 图片超过 9 张 | 最多上传 9 张图片 |
| 任一张地址无效：不是 `files/image` 返回的聊天图片地址、文件已不存在（如已被清理）、地址为空或超过 255 字 | 图片地址无效，请重新上传图片 |
| 文字超过 500 字 | 问题最多 500 字 |

```json
{"code":40001,"message":"最多上传 9 张图片","data":null}
```

服务端处理：

- 保存的用户消息 `content` 为去掉首尾空白的文字，只发图片时为 `""`。整条消息（文字 + 全部图片）在一个短事务中保存；任一张图片无效时整条消息都不保存。
- 发给模型的当前问题：content 依次为每张图片一个 `image_url` 部分（按顺序），最后是文字部分。只发图片时文字为「请帮我看看这张照片里的作物有什么问题」（1 张）或「请帮我看看这些照片里的作物有什么问题」（2 张及以上）。
- 上下文（最近 10 轮）：历史中的带图提问在文字后附「（用户附带了一张图片）」/「（用户附带了 N 张图片）」；只发图片的历史提问以「（用户发送了一张图片）」/「（用户发送了 N 张图片）」作为文字（不再被跳过）。历史图片不重复发送。
- 每用户每分钟 10 次的提问限流不变（多图也只算 1 次）；参数错误（上表前两行、500 字）不计入限流。

### 2.2 打开历史对话 `GET ai/sessions/{id}`

**ChatMessageVO** 新增 `image_urls`：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| role | string | 否 | user / assistant |
| content | string | 否 | 只发图片的提问为 `""` |
| image_url | string | 是 | 第一张图片的完整地址（兼容旧版 App）；没有图片为 null |
| image_urls | string[] | 否 | **新增**。全部图片的完整地址，按顺序；**没有图片为空数组 `[]`，不为 null**；迁移前的旧消息为 `[image_url]` |
| created_at | datetime | 否 | 不变 |
| reasoning_content | string | 是 | 不变 |
| reasoning_ms | integer | 是 | 不变 |

```json
{
  "role": "user",
  "content": "",
  "image_url": "http://10.0.2.2:8080/files/chat/20261014/3f2b9c0e8a7d4f6e9b1c2d3e4f5a6b7c.jpg",
  "image_urls": [
    "http://10.0.2.2:8080/files/chat/20261014/3f2b9c0e8a7d4f6e9b1c2d3e4f5a6b7c.jpg",
    "http://10.0.2.2:8080/files/chat/20261014/7a1d2c3b4e5f60718293a4b5c6d7e8f9.jpg"
  ],
  "created_at": "2026-10-14 09:30:12",
  "reasoning_content": null,
  "reasoning_ms": null
}
```

App 显示规则：以 `image_urls` 为准（为空则不显示图片）；只有旧版字段时才回退到 `image_url`。

### 2.3 其他接口

| 接口 | 变化 |
|---|---|
| `GET ai/sessions`（历史对话列表） | 不变。带图消息的 `last_message` 仍以「[图片]」开头；只发图片时为「[图片]」 |
| `POST files/image` | 不变：multipart 字段 `image`，一次一张，jpg / png，≤ 2MB，返回 UploadVO（url）；只保存文件、不写数据库 |
| `DELETE ai/sessions/{id}` | 不变；同时删除这些消息的全部图片行，事务提交后删除不再被任何消息引用的图片文件 |
| `POST user/deactivate` | 不变；同时删除该用户全部消息的图片行与图片文件 |

## 三、数据表

新增 `t_ai_message_image`（新建库直接执行 `server/sql/schema.sql`；已有库执行迁移脚本 `server/sql/migrations/005_ai_message_images.sql`）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT AUTO_INCREMENT | 主键 |
| message_id | BIGINT NOT NULL | t_ai_message.id（user 消息） |
| sort_no | INT NOT NULL | 图片顺序，从 0 开始（用户选择的顺序） |
| image_url | VARCHAR(255) NOT NULL | 图片相对路径，如 `chat/20261014/{uuid}.jpg` |
| created_at | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP | 与提问同时写入，之后不再修改（因此没有 updated_at） |

索引：`idx_message (message_id, sort_no)`（读取消息的图片）、`idx_image_url (image_url)`（清理前核对引用）。InnoDB、utf8mb4。

- `t_ai_message.image_url` 继续保存**第一张**图片（历史对话列表据此显示「[图片]」，旧版本也能读到）；全部图片按顺序写入 `t_ai_message_image`，与问题在同一个事务中。
- 读取消息（打开历史对话、组装上下文、重新生成时比较问题）时一次查出这些消息的图片行；某条消息没有图片行但有 `image_url` 时按 `[image_url]` 处理。
- 删除对话 / 注销账号：先删图片行再删消息；待删除的文件路径取 `t_ai_message_image` 与 `t_ai_message.image_url` 两处；删除对话时仍被其他消息引用（两处任一）的图片保留。

迁移脚本用法（**先迁移，再部署新版本服务端**；先备份：`mysqldump -u root -p pestai > pestai-backup.sql`）：

```bash
cd server
mysql -u root -p --default-character-set=utf8mb4 pestai < sql/migrations/005_ai_message_images.sql
```

PowerShell：`Get-Content sql/migrations/005_ai_message_images.sql -Encoding UTF8 | mysql -u root -p --default-character-set=utf8mb4 pestai`

- 可重复执行：建表用 `CREATE TABLE IF NOT EXISTS`；回填只为「还没有任何图片行」的带图消息插入一行（`sort_no = 0`、`created_at` 与消息相同），第二次执行不会重复插入；`t_ai_message.image_url` 只更新列注释。不修改、不删除任何已有数据。
- 执行后检查：`SHOW CREATE TABLE t_ai_message_image;`；`SELECT COUNT(*) FROM t_ai_message WHERE image_url IS NOT NULL AND image_url <> '';` 应等于 `SELECT COUNT(DISTINCT message_id) FROM t_ai_message_image;`。
- 未迁移就部署新版本：保存带图提问、打开历史对话、删除对话、注销账号会报错（表不存在）。

## 四、未发送图片的定期清理

App 选图后立即上传，用户点 ✕ 删除或最终没有发送时，图片已经在服务器的 `{文件根目录}/chat/{yyyyMMdd}/` 下，却不被任何消息引用。服务端定时任务 `ChatImageJanitor` 负责清理：

| 项 | 规则 |
|---|---|
| 时间 | 每天 04:30（Asia/Shanghai）；配置项 `pestai.ai.orphan-image-cron`（环境变量 `PESTAI_ORPHAN_IMAGE_CRON`，Spring cron 格式，默认 `0 30 4 * * *`，设为 `-` 关闭） |
| 范围 | 只扫描 `chat/` 下目录名为 `yyyyMMdd` 且日期**不晚于前天**的目录（今天、昨天的保留，给正在编辑、还没发送的问题留出时间）；目录名不是日期的跳过；`avatar/`、`rec/` 不处理 |
| 判断 | 目录中的 jpg / png 按 500 个一批，查询相对路径是否被 `t_ai_message_image.image_url` 或 `t_ai_message.image_url` 引用；未被引用的删除 |
| 其他 | 上传中断留下的 `.upload-*.tmp` 临时文件直接删除；删空的日期目录一并删除；其他类型的文件不动 |
| 失败处理 | 从不抛出异常；查询引用失败（如数据库不可用）时本次不再删除任何图片；每次结束记一行 INFO 日志（检查的目录数、图片数，删除的图片数、临时文件数、空目录数） |

对 App 的影响：图片上传后至少保留到「上传日期 + 2 天」的 04:30（最短约 28 小时，例如 10 月 14 日 23:59 上传的图片在 10 月 16 日 04:30 被清理）。选好图片后隔了这么久才发送时，服务端返回 40001「图片地址无效，请重新上传图片」，App 提示后让用户重新选择该图片（重新上传）即可。

## 五、App 端实现

### 5.1 输入栏（Q01 / Q02 共用）

| 类（module_ai） | 职责 |
|---|---|
| `compose/ImageQueue` | 待发送图片队列（两个 ViewModel 各持有一个）：加入即压缩上传（`AiRepository.uploadImage`），最多同时 3 张、其余排队；读取失败移出队列并提示；其他失败标记为失败、点按缩略图重试；✕ 移除并取消上传；满 9 张时「+」变淡、点按提示「最多只能添加 9 张图片」 |
| `compose/PendingImage` | 队列中的一张图片（不可变）：id、本地 Uri、上传后的地址、状态 WAITING / UPLOADING / FAILED / DONE |
| `compose/ImageQueueStrip`、`PendingImageAdapter` | 输入栏上方的 64dp 缩略图条：上传中遮罩 + 进度、失败遮罩 + error 图标、完成后点按全屏预览 |
| `compose/ImageComposer`、`ComposerBindingAdapters` | 两个 ViewModel 共同实现的输入栏接口；输入框提示随张数变化（`app:aiDefaultHint` + `app:aiImageHintCount`） |
| `view_ai_composer.xml`、`compose/ComposerBar`、`compose/ComposerButtons` | 共用输入栏布局与控制：缩略图条 + 圆角胶囊（拍照 · 输入框 · 「+」· 发送）；按钮显示规则由纯 Java 的 `ComposerButtons` 计算 |
| `util/AiImagePicker`、`PickImagesContract` | 拍照（申请 CAMERA 权限、FileProvider + TakePicture、取消时删除空文件、进程被回收后经 SavedStateRegistry 恢复）与相册（可选张数 = 9 − 已添加，≥ 2 时多选、= 1 时单选，系统退回无上限选择器时由队列截断） |

输入栏（Q01 / Q02 共用，include 布局 `view_ai_composer.xml` + `compose/ComposerBar`）：上方是待发送图片缩略图条（64dp，没有图片时隐藏）；下方是一个圆角胶囊（最小高 52dp，左右边距 16dp，surfaceContainerHigh 底，圆角 26dp），从左到右依次为拍照按钮（直接拍照，先申请 CAMERA 权限）、多行输入框（无背景，最多 5 行）、「+」按钮（直接打开照片选择器多选，可选张数 = 9 − 已添加）、36dp 圆形发送按钮。单行时整行垂直居中，并按这台设备上中文字形的实际边界（`compose/GlyphCentering`）微调输入框上下内边距，把文字和提示语移到正中（不同机型的系统字体上下留白不对称，只按行框居中会偏上或偏下；输入框关闭了回退字体行距与 Android 15 的按语言最小行高，保证提示语与正文行框一致），文字换行后按钮贴底对齐，不再弹出「拍照 / 从相册选择」面板。按钮显示规则（`compose/ComposerButtons`）：输入框有文字时只显示发送按钮；没有文字也没有图片时只显示拍照和「+」；没有文字但有图片时三个按钮都显示；Q02 生成中右侧按钮一直显示为停止按钮（「停止生成」），拍照和「+」仍按文字规则；已有 9 张图片时拍照和「+」变淡（透明度 0.38），点按提示「最多只能添加 9 张图片」；Q02 打开历史对话加载中或失败时发送按钮显示但不可点。按钮出现和消失有 150ms 淡入淡出（不做位置过渡：切换输入方式时键盘同时弹出 / 收起，位置过渡会让输入框停在中途宽度）。

发送规则：有图片在上传时提示「图片还在上传，请稍候」，有图片上传失败时提示「有图片上传失败，请点按重试或移除」，都不发送；
否则发送 content（只发图片时为空串）+ 按添加顺序的 image_urls，不再发送 image_url。
Q01 发送时通过 Intent（`ChatActivity.EXTRA_IMAGE_URIS` / `EXTRA_IMAGE_URLS`，两个 `ArrayList<String>`）把文字与图片（本地 Uri + 地址）交给 Q02，
Q02 直接自动发送，不重复上传。重新生成 / 重试原样重发上一问的 content 与 image_urls。

### 5.1.1 语音输入（文字 / 语音两种输入方式）

像微信一样，输入栏有两种输入方式，麦克风图标和键盘图标在胶囊里的同一个位置，点同一处来回切换：

- **文字：** 拍照 · 输入框 · 麦克风 ·「+」· 发送。按钮规则不变：有文字时只显示发送；没有文字时显示拍照、麦克风和「+」；有待发送图片时再加发送。
- **语音：** 只有「按住 说话」· 键盘（拍照、「+」和发送都隐藏，界面最简洁）；Q02 生成回答时右侧另有停止按钮，否则无法停止生成。
  - 「按住 说话」占拍照和输入框的位置，40dp 高、圆角 20dp、surfaceContainerHighest 底，文字 titleSmall 居中。
  - 之前打的字（草稿）和待发送图片都保留但不显示发送按钮；识别后自动切回文字时，按文字规则显示发送按钮，或点键盘切回后发送。
  - 切到语音时草稿不清空，只是隐藏，切回文字后原样显示。
  - Q02 生成回答时右侧仍是停止按钮，也可以语音输入（只填文字，不发送）。

**切换：**

- **点麦克风：** 切到语音，收起键盘并清除输入框焦点。
  - 第一次没有麦克风权限时当场申请：授权后进入语音；拒绝则留在文字，并提示「需要麦克风权限才能语音输入，可在系统设置中开启」。
- **点键盘：** 切回文字，输入框获得焦点并弹出键盘。
- 游客点麦克风或键盘时弹出登录框。
- 两个图标的无障碍描述分别是「切换到语音输入」「切换到键盘输入」。切换时输入框和「按住 说话」、麦克风和键盘按 150ms 淡入淡出互换。

**记住输入方式：**

- 记录用户最后一次主动切换：SharedPreferences `ai_composer` 的 `voice_mode`，Q01 和 Q02 共用，与账号无关。
- 打开页面时使用记住的方式；Q01 回到页面时会同步在 Q02 中的切换。发送成功、输入框清空后也回到记住的方式。
- 旋转屏幕保持当前方式（状态在 ViewModel 中）。
- 记住的是语音、但权限已在系统设置中撤销时，仍显示语音，按住时再申请，见下方「按住 说话」。

**「按住 说话」：**

- **开始录音：**
  - 按下即振动并开始录音（AudioRecord，PCM 16 位 / 16kHz / 单声道 WAV）。
  - 按钮变为「松开 识别」，底色加深。
  - 输入栏上方显示深色半透明浮层：音量条、计时「0:05」、「松开 识别 · 上滑 取消」。
  - 没有权限时申请，本次不录音。授权后提示「已开启麦克风，请按住说话」，拒绝时提示需要权限，都留在语音。
- **录音中：**
  - 上滑超过 80dp：按钮变为「松开 取消」（errorContainer 配色），浮层变红，此时松开即丢弃。
  - 50 秒起提示「还能说 N 秒」，60 秒自动结束并识别。
  - 不足 1 秒丢弃，并提示「说话时间太短」。
- **识别：**
  - 松开后按钮显示「识别中…」并且不可点，浮层也显示「识别中…」。
  - 录音经 `POST ai/asr`（multipart 字段 `audio`）识别，识别中忽略新的按下。
- **识别成功：**
  - 文字追加到草稿后面，临时切到文字输入。
  - 输入框获得焦点、光标移到末尾并弹出键盘，方便改错字；不自动发送，发送按钮按文字规则出现。
  - 这次临时切换不改变记住的方式：发送后又回到「按住 说话」。
- **失败提示：** 按服务器返回提示，例如 40001「没有听清，请再说一遍」、50201「语音识别暂不可用，请稍后再试」、断网时「网络连接失败，请检查网络后重试」。失败时留在语音。
- **释放麦克风：**
  - 录音文件无论成功、失败、取消都会删除。
  - 以下情况立即停止录音并释放麦克风：页面停止、旋转屏幕、手势被系统取消、按钮被移除或不可见（如切换 Tab）。
  - 正在识别的结果在旋转屏幕后仍会填入。
- **TalkBack：** 改为点按开始、再次点按结束并识别（录音中按钮为「结束并识别」），并播报状态变化。

实现见 module_ai：

- `compose/InputMode`：输入方式状态；`compose/InputModeStore`：记住的方式。
- `compose/ComposerButtons`：两种方式的按钮规则；`compose/ComposerBar`：切换、键盘与焦点。
- `compose/VoiceComposer`：ViewModel 接口。
- `voice/`：`WavWriter`、`VoiceGesture`、`VoiceRecorder`、`VoiceInputController`、`VoiceRecognizer`。
- `util/AiMicPermission`：麦克风权限。

### 5.2 对话列表与全屏预览

- 一条消息的全部图片是一个列表项，`chat/ImageGridView` 九宫格显示（规则见 `chat/ImageGridSpec`）：1 张 180 × 135dp（圆角 16dp）；
  2–4 张 2 列 96dp；5–9 张 3 列 80dp；圆角 12dp、间距 4dp，整体靠右，不满一行时末行也靠右。优先显示本地图片（不闪烁），失败时退回服务器地址。
- 打开历史对话时图片取 image_urls（为空时退回 image_url）；旧数据「默认问题 + 图片」与新数据「空文字 + 图片」都不显示文字气泡。
- 点按任意一张打开 `preview/ImagePreviewActivity`：黑底 ViewPager2 左右翻页，顶部 ✕ 与「3/9」计数（只有 1 张时不显示计数）；
  `ZoomImageView` 双指缩放 1–4 倍、双击在 1 倍与 2.5 倍之间切换，放大后拖动不与翻页冲突，拖到左右边缘再交给翻页。
