# 部署到云服务器（只需要一个公网 IP）

本文带你把后端 pestai-server 部署到一台云服务器上，让手机上的 App 在任何网络下都能访问。
全程在自己电脑的 PowerShell 里运行**一条命令**，服务器上的软件安装、建库、启动服务都由脚本自动完成；
之后更新后端也只需要再运行同一条命令。

> 适用：Ubuntu 22.04 / 24.04 云服务器 + Windows 10 / 11 电脑。首次部署约 5–10 分钟。

---

## 一、服务器上会运行什么

```
手机 App ──HTTP 80 端口──▶ Nginx ──▶ 127.0.0.1:8080  pestai-server（JDK 21 运行，systemd 守护）
                                                      ├─▶ MySQL 8（127.0.0.1:3306，库 pestai）
                                                      └─▶ /data/pestai/files（上传的图片）
```

| 组件 | 作用 |
|---|---|
| **JDK 21** | 运行后端程序 `pestai-server.jar`（Spring Boot） |
| **MySQL 8** | 保存用户、识别记录、AI 对话等数据；只允许本机连接 |
| **Nginx** | 对外接收 80 端口的请求，转发给只监听本机的后端；AI 回答的流式输出不缓冲、逐段转发 |
| **systemd** | 把后端注册成系统服务 `pestai`：开机自动启动，崩溃 5 秒后自动重启，日志统一用 `journalctl` 查看 |

---

## 二、购买云服务器

任选一家，按下面的配置购买即可：

| 项目 | 选择 |
|---|---|
| 产品 | 阿里云 **ECS**，或腾讯云 **轻量应用服务器** |
| 配置 | **2 核 4G** 起（2G 内存也能跑，但需要调小 JVM 内存，见「常见问题」） |
| 系统镜像 | **Ubuntu 22.04**（64 位；24.04 也支持）。不要选 CentOS、Debian、宝塔等其他镜像 |
| 公网 IP | 需要（轻量应用服务器自带；ECS 购买时勾选「分配公网 IPv4 地址」） |
| 带宽 | 1–5 Mbps 足够课程演示 |

买好后记下控制台里显示的**公网 IP**（如 `47.100.1.2`），然后**放行端口**：

- 阿里云 ECS：实例 → **安全组** → 入方向 → 添加规则：`22/22`（SSH）、`80/80`（HTTP），授权对象 `0.0.0.0/0`。
- 腾讯云轻量：实例 → **防火墙** → 确认有 `TCP 22` 与 `TCP 80` 两条「允许」规则（默认通常已有）。

> 不要放行 3306（MySQL）和 8080（后端），它们只在服务器内部使用。

---

## 三、设置登录方式

部署脚本通过 SSH 登录服务器。

| 云厂商 | 默认登录用户 | 设置方法 |
|---|---|---|
| 阿里云 ECS | `root` | 实例 → 更多 → 重置实例密码（重置后需重启实例） |
| 腾讯云轻量 | `ubuntu`（root 默认不能用密码登录） | 实例 → 重置密码，用户选 `ubuntu`；部署时加 `-User ubuntu` |

**推荐配置 SSH 密钥**：不配置也能部署，但每次部署要输入两次密码（上传一次、安装一次）。
在自己电脑的 PowerShell 中执行（把 IP 换成你的；腾讯云把 `root` 换成 `ubuntu`）：

```powershell
# 1. 生成密钥：一路回车即可（提示文件已存在时输入 n，直接用已有的密钥）
ssh-keygen -t ed25519

# 2. 把公钥追加到服务器（这一次需要输入服务器密码）
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh root@47.100.1.2 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"

# 3. 测试：不再询问密码、直接输出 ok 即成功
ssh root@47.100.1.2 "echo ok"
```

第一次连接会提示 `Are you sure you want to continue connecting`，输入 `yes` 回车。

---

## 四、本机准备

1. **server\\.env**：后端的大模型 Key、短信 AccessKey 都写在这里（格式见 `server\.env.example`，说明见 `server\README.md`）。
   部署时会**原样上传**到服务器的 `/opt/pestai/.env`。没有配置的功能自动使用 Mock / 日志实现（演示也能跑通）。
   这个文件必须存在：暂时不需要密钥时，复制 `server\.env.example` 为 `server\.env` 即可。
   数据库账号、JWT 密钥、公网地址由服务器自动生成和设置，**不需要**在 .env 中为服务器修改。
2. **JDK 21 + Maven**：用于在本机打包后端。在 PowerShell 中执行 `mvn -v` 能看到版本即可。
   找不到 `mvn` 时脚本会尝试 `D:\maven\apache-maven-3.9.14\bin\mvn.cmd`，也可以用 `-Mvn` 指定路径。
3. **OpenSSH 客户端**：Windows 10 / 11 自带，执行 `ssh -V` 能看到版本即可；
   没有时在「设置 → 系统 → 可选功能 → 添加可选功能」中安装「OpenSSH 客户端」。
4. 如果本机正在运行 `server\target\pestai-server.jar`，先停止它（打包时要删除旧文件）。

---

## 五、一键部署

在**项目根目录**打开 PowerShell，执行（IP 换成你的）：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Ip 47.100.1.2
```

脚本依次完成：本机打包 → 上传到服务器 `/tmp/pestai-deploy` → 在服务器上执行 `install.sh` → 删除服务器上的临时文件。
看到绿色的 **「部署完成」** 即成功。首次需要安装 JDK、MySQL、Nginx，等几分钟属于正常。

常用参数：

| 参数 | 说明 |
|---|---|
| `-Ip 47.100.1.2` | 服务器公网 IP；不写时运行后询问 |
| `-User ubuntu` | SSH 用户，默认 `root`；不是 root 时自动用 `sudo` 安装（可能要求输入该用户的密码） |
| `-Port 22` | SSH 端口，默认 22 |
| `-Demo` | 导入演示账号：手机号 `13800005678`，密码 `Pest2026demo` |
| `-SkipBuild` | 不重新打包，直接上传已有的 `server\target\pestai-server.jar` |
| `-Mvn D:\...\mvn.cmd` | 指定 Maven 路径 |
| `-PublicUrl https://域名` | App 访问地址，默认 `http://<Ip>`；配置了域名与 HTTPS 后使用（见「安全说明」） |

例如腾讯云轻量 + 演示账号：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Ip 47.100.1.2 -User ubuntu -Demo
```

---

## 六、验证

用电脑或手机的浏览器打开：

```
http://47.100.1.2/pestai/v1/app/version
```

看到类似 `{"code":0,"message":"ok","data":{...}}` 即表示后端、数据库、Nginx 全部正常。

部署结束时脚本还会打印「后端运行模式」，例如 `识图：MockVisionClient（未配置 DASHSCOPE_API_KEY）`，
据此确认大模型、短信是否已经使用真实的 Key。

---

## 七、让 App 连接服务器（二选一）

**方式 1：重新打包 App**（适合要分发给别人的安装包）

在项目根目录的 `local.properties` 中添加一行（末尾不要加 `/`），然后重新构建并安装 App：

```properties
BASE_URL=http://47.100.1.2
```

**方式 2：不重新打包，在 App 里修改**（适合已经装好的 App）

1. 打开 App →「我的」→「关于」（未登录、游客也能进入）；
2. **连续快速点击版本号那一行 5 次**（第 3、4 次会提示「再点 N 次打开服务器设置」）；
3. 在「服务器地址」中填写 `47.100.1.2`（也可以填 `47.100.1.2:8080`、`http://47.100.1.2`），点「保存」；
4. App 会提示「服务器地址已更改，请重新登录」并回到登录页。

修改后「关于」页版本号下方会显示「服务器：http://47.100.1.2」；点「恢复默认」可改回打包时的地址。
地址改变后本机的登录状态会被清除（旧服务器的登录令牌在新服务器上无效）。

> 两种方式都要求 App 中的地址与服务器的 `PESTAI_PUBLIC_BASE_URL` 一致（部署脚本按 `-Ip` 自动设置为 `http://IP`），否则图片显示不出来。

---

## 八、后台管理

后台管理网页内置在后端中，不需要单独部署：浏览器打开

```
http://47.100.1.2/admin/
```

**首次登录**：账号 `admin`，初始密码在部署结束时打印（「后台管理：http://47.100.1.2/admin/，账号 admin，初始密码 xxx」），
也可以在服务器上执行 `sudo grep PESTAI_ADMIN_INITIAL_PASSWORD /etc/pestai/pestai.env` 查看。
登录后必须先修改密码（至少 10 位，同时包含字母和数字），之后这个初始密码不再有效（重新部署时仍会打印，修改过请忽略）。

后台可以：

- 查看数据统计：用户数、活跃用户、识别次数与成功率、AI 提问、短信发送量（近 7 / 30 天趋势）；
- 管理用户：搜索、查看识别记录与 AI 对话、停用 / 启用、强制下线、修改资料、重置密码（生成 12 位临时密码）、
  彻底删除识别记录或对话、注销账号；
- 修改系统配置：DashScope / DeepSeek 的 API Key 与模型名、语音识别模型、阿里云短信的 AccessKey、签名与模板。
  **后台设置的值优先于 `server\.env`，保存后立即生效、不用重启**；点「恢复」改回 `.env` / 默认值。
  修改密钥类配置需要再输入一次管理员密码；配置页的「测试」会用当前的 Key 发一次最小请求（短信为发送一条测试验证码）；
- 查看操作日志：登录、退出与所有修改操作都会记录（不记录密码与密钥）。

安全措施：同一账号或同一 IP 连续输错 5 次密码锁定 15 分钟；30 分钟无操作自动退出；
后台保存的 API Key / AccessKey 用 `PESTAI_CONFIG_SECRET`（部署脚本首次安装时随机生成，在 `/etc/pestai/pestai.env` 中）加密保存，
**不要修改这个值**，否则后台保存的密钥无法解密（按未设置处理，需要在后台重新填写）。

> **注意：只有 IP、没有域名时后台走 HTTP，管理员密码与在后台填写的 API Key 都以明文在网络上传输**，
> 同一 Wi-Fi 下的人有可能截获。不要在公共网络上登录后台；正式使用前请按「安全说明」配置域名与 HTTPS。
> 忘记管理员密码时，可在服务器上执行 `mysql pestai -e "DELETE FROM t_admin WHERE username = 'admin'"` 后
> `systemctl restart pestai`：后端会用 `PESTAI_ADMIN_INITIAL_PASSWORD` 重新创建 admin（首次登录后同样必须修改）。

---

## 九、更新后端

修改代码或 `server\.env` 后，**重新运行同一条部署命令**即可：

- 重新打包并上传新的 jar 与 `.env`，重启服务；
- 自动执行 `server\sql\migrations\` 中**新增的**数据库迁移脚本（执行前自动备份数据库到 `/var/backups/pestai/`）；
- 已有的数据、图片、服务器配置（`/etc/pestai/pestai.env`）都会保留。

数据库如何判断：服务器上的表 `t_schema_migration` 记录了已执行的迁移文件名。
首次部署时直接执行 `schema.sql`（已包含全部迁移的变更）并把当时所有迁移登记为已执行；
之后每次部署只执行没有登记过的迁移，按文件名顺序执行，成功一个登记一个。

---

## 十、服务器上的目录与常用命令

登录服务器：`ssh root@47.100.1.2`（腾讯云：`ssh ubuntu@47.100.1.2`，之后命令前加 `sudo`）。

| 路径 | 内容 |
|---|---|
| `/opt/pestai/pestai-server.jar` | 后端程序 |
| `/opt/pestai/.env` | 大模型 / 短信密钥（每次部署用本机 `server\.env` 覆盖；只有 pestai 用户可读） |
| `/etc/pestai/pestai.env` | 服务器专用配置：数据库密码、JWT 密钥、后台初始密码与配置加密密钥、公网地址、JVM 参数（root 可读写；优先于 .env） |
| `/data/pestai/files/` | 上传的图片（头像 avatar、识别照片 rec、聊天图片 chat） |
| `/etc/systemd/system/pestai.service` | systemd 服务定义 |
| `/etc/nginx/sites-available/pestai` | Nginx 配置 |
| `/var/backups/pestai/` | 执行数据库迁移前的自动备份 |

```bash
systemctl status pestai                # 后端状态（active (running) 为正常）
systemctl restart pestai               # 重启后端（修改 /etc/pestai/pestai.env 后执行）
journalctl -u pestai -f                # 实时查看后端日志，Ctrl+C 退出；未配置短信时验证码打印在这里
journalctl -u pestai -n 200 --no-pager # 最近 200 行日志
nano /etc/pestai/pestai.env            # 修改服务器配置（Ctrl+O 保存，Ctrl+X 退出）
systemctl reload nginx                 # 修改 Nginx 配置后生效（先 nginx -t 检查）
tail -f /var/log/nginx/access.log      # 访问日志
mysql pestai                           # 以 root 进入数据库（Ubuntu 的 root 免密码）
```

---

## 十一、备份与恢复

**备份**（在服务器上执行）：

```bash
mysqldump --single-transaction --default-character-set=utf8mb4 pestai | gzip > /root/pestai-$(date +%F).sql.gz
tar czf /root/pestai-files-$(date +%F).tar.gz -C /data/pestai files
```

下载到本机（在本机 PowerShell 执行，文件名换成实际日期）：

```powershell
scp root@47.100.1.2:/root/pestai-2026-10-14.sql.gz .
scp root@47.100.1.2:/root/pestai-files-2026-10-14.tar.gz .
```

每天凌晨 3 点自动备份数据库（可选，`crontab -e` 中添加一行，保留最近 7 天）：

```bash
0 3 * * * mysqldump --single-transaction --default-character-set=utf8mb4 pestai | gzip > /root/pestai-$(date +\%F).sql.gz && find /root -name 'pestai-*.sql.gz' -mtime +7 -delete
```

**恢复**（会覆盖现有数据）：

```bash
systemctl stop pestai
gunzip < /root/pestai-2026-10-14.sql.gz | mysql --default-character-set=utf8mb4 pestai
tar xzf /root/pestai-files-2026-10-14.tar.gz -C /data/pestai
chown -R pestai:pestai /data/pestai/files
systemctl start pestai
```

迁移记录表 `t_schema_migration` 也在备份中，恢复后下次部署会自动补执行备份之后新增的迁移。

---

## 十二、安全说明

- **只有 IP、没有域名时只能用 HTTP**：登录密码、短信验证码、登录令牌在网络上以明文传输，同一 Wi-Fi 下的人有可能截获；
  后台管理（`/admin/`）的管理员密码和在后台填写的 API Key、AccessKey 同样是明文传输。
  课程演示、内部测试可以接受；**正式上线**需要：
  1. 购买域名并解析到服务器；国内服务器还需要完成 **ICP 备案**（未备案的域名无法通过 80 / 443 访问）；
  2. 配置 HTTPS，例如在服务器上执行
     `apt install -y certbot python3-certbot-nginx && certbot --nginx -d pest.example.com`
     （certbot 会修改 Nginx 配置，之后部署脚本检测到配置被修改过就不再覆盖）；
  3. 之后部署加 `-PublicUrl https://pest.example.com`，App 的 `BASE_URL` 也改为该地址；
     并把 `app\src\main\res\xml\network_security_config.xml` 中的 `cleartextTrafficPermitted` 改回 `false`。
- **MySQL 只监听本机**（Ubuntu 默认 `bind-address = 127.0.0.1`），后端使用的 `pestai` 账号只能从本机登录，密码随机生成。
- **后端只监听 127.0.0.1:8080**，外网只能经过 Nginx 的 80 端口访问；安全组不要放行 8080、3306。
- 密钥文件权限：`/opt/pestai/.env` 只有 pestai 用户可读，`/etc/pestai/pestai.env` 只有 root 可读；
  上传用的临时目录在安装结束后（无论成功与否）自动删除。
- JWT 登录令牌密钥由服务器首次安装时随机生成；需要让所有用户重新登录时，修改 `/etc/pestai/pestai.env` 的 `JWT_SECRET` 后重启。
- 后端以没有登录权限的系统用户 `pestai` 运行；配置好 SSH 密钥后，可以在云控制台关闭密码登录进一步加固。

---

## 十三、常见问题

**浏览器打开显示 502 Bad Gateway**
Nginx 正常，但后端没有运行或启动失败。执行 `systemctl status pestai` 与 `journalctl -u pestai -n 100 --no-pager` 查看原因
（常见：内存不足被系统杀掉、`.env` 中配置写错）。修正后 `systemctl restart pestai`。

**浏览器打不开 / 一直转圈**
云控制台的安全组（阿里云）或防火墙（腾讯云）没有放行 80 端口；或 IP 写错。
在服务器上执行 `curl http://127.0.0.1/pestai/v1/app/version`：能返回 `"code":0` 说明服务正常，问题在安全组。

**部署时 ssh / scp 连接超时或 Permission denied**
连接超时：检查 IP、安全组是否放行 22 端口。Permission denied：密码错误，或该用户不允许密码登录
（腾讯云轻量请用 `-User ubuntu`）。

**提示「本脚本只支持 Ubuntu 22.04 / 24.04」**
在云控制台把系统重装为 Ubuntu 22.04（会清空服务器上的数据），然后重新部署。

**短信收不到验证码**
`server\.env` 中阿里云短信的 AccessKey、签名、模板没有配置齐全时，验证码不会真正发送，而是打印在服务器日志里：
`journalctl -u pestai -f`。已配置但仍收不到时，在日志中查看阿里云返回的错误信息
（常见：AccessKey 没有开通「号码认证服务」、签名与模板不匹配、同一手机号发送过于频繁）。修改 `.env` 后重新部署。

**AI 识别 / 问答返回的是固定示例内容**
没有配置大模型 Key（`DASHSCOPE_API_KEY` 识图、`DEEPSEEK_API_KEY` 问答），后端使用了 Mock。
在 `server\.env` 中填写后重新部署；部署结束时的「后端运行模式」会显示当前使用的模型。

**图片（头像、识别照片）显示不出来**
图片地址由服务器拼成 `PESTAI_PUBLIC_BASE_URL + /files/...`，必须与 App 访问服务器的地址一致。
例如服务器换了 IP，用新 IP 重新运行部署命令即可（数据库只保存相对路径，旧图片也会使用新地址）。

**App 提示网络错误**
确认 App 使用的地址：「我的 → 关于」版本号下方显示自定义的服务器地址；没有显示时使用的是打包时的 `BASE_URL`
（默认 `http://10.0.2.2:8080` 只适用于电脑上的模拟器）。

**服务器只有 2G 内存，后端反复重启**
编辑 `/etc/pestai/pestai.env`，把 `JAVA_OPTS` 改为 `"-Xms128m -Xmx512m -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"`，
然后 `systemctl restart pestai`；也可以增加 2G 交换空间：

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

**PowerShell 提示「无法加载文件，因为在此系统上禁止运行脚本」**
请按本文的写法用 `powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 ...` 运行，不要直接双击或 `.\deploy.ps1`。

**想手动在服务器上重新执行安装**
`install.sh` 可以重复执行。把 `deploy\` 与 `server\sql\` 中的文件、jar、.env 按 `deploy.ps1` 的方式放到服务器某个目录后，执行
`bash 目录/install.sh --public-url http://47.100.1.2`；不写 `--public-url` 时沿用上次的地址。
