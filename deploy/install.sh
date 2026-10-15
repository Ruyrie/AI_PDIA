#!/usr/bin/env bash
# =====================================================================
# pestai-server 云服务器一键安装 / 更新脚本（Ubuntu 22.04 / 24.04，root 运行）
#
# 通常由本机的 deploy/deploy.ps1 上传到 /tmp/pestai-deploy 后自动执行；也可以登录服务器手动执行：
#   bash /tmp/pestai-deploy/install.sh --public-url http://47.100.1.2 [--demo]
#
# 参数：
#   --public-url URL  App 访问服务器的地址（http://公网IP），首次安装必填；之后不填则沿用上次的值
#   --demo            导入演示账号（sql/seed-dev.sql：13800005678 / Pest2026demo，可重复执行）
#
# 暂存目录（本脚本所在目录）中应有：pestai-server.jar、.env、pestai.service、nginx-pestai.conf、
#   pestai.env.example、sql/schema.sql、sql/seed.sql、sql/seed-dev.sql、sql/migrations/*.sql
#
# 可重复执行：已安装的软件包、已有的数据与配置都会保留，只更新程序与密钥文件、执行新的数据库迁移、重启服务。
# 安装结果：
#   /opt/pestai/pestai-server.jar、/opt/pestai/.env   后端程序与大模型 / 短信密钥（属主 pestai，.env 权限 600）
#   /etc/pestai/pestai.env                            服务器专用配置（数据库密码、公网地址、JVM 参数，权限 600）
#   /data/pestai/files                                 上传的图片
#   /etc/systemd/system/pestai.service                 systemd 服务（开机自启、崩溃自动重启）
#   /etc/nginx/sites-available/pestai                  Nginx：80 端口转发到 127.0.0.1:8080
# =====================================================================
set -euo pipefail
shopt -s nullglob

STAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_DIR=/opt/pestai
FILE_ROOT=/data/pestai/files
CONF_DIR=/etc/pestai
ENV_FILE=$CONF_DIR/pestai.env
NGINX_STAMP=$CONF_DIR/nginx-pestai.conf.installed
BACKUP_DIR=/var/backups/pestai
SERVICE=pestai
SERVICE_USER=pestai
SERVICE_FILE=/etc/systemd/system/pestai.service
NGINX_SITE=/etc/nginx/sites-available/pestai
DB_NAME=pestai
# 健康检查：白名单接口（不需要登录），会查询一次数据库，成功时返回 {"code":0,...}
HEALTH_PATH=/pestai/v1/app/version
HEALTH_TIMEOUT=90

DEFAULT_DB_URL='jdbc:mysql://localhost:3306/pestai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
DEFAULT_JAVA_OPTS='-Xms256m -Xmx768m -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8'

PUBLIC_URL=""
DEMO=0
FRESH_DB=0
START_TIME=""

# ---------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------

log() {
    printf '\n\033[1;36m==> %s\033[0m\n' "$*"
}

ok() {
    printf '\033[1;32m[完成]\033[0m %s\n' "$*"
}

warn() {
    printf '\033[1;33m[注意]\033[0m %s\n' "$*" >&2
}

die() {
    printf '\n\033[1;31m[失败]\033[0m %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
用法：bash install.sh --public-url http://公网IP [--demo]
  --public-url URL  App 访问服务器的地址，首次安装必填；之后不填则沿用 /etc/pestai/pestai.env 中的值
  --demo            导入演示账号（13800005678 / Pest2026demo）
EOF
}

# ---------------------------------------------------------------------
# /etc/pestai/pestai.env 读写（systemd EnvironmentFile 格式：KEY=值）
# 不用 source 读取：DB_URL 中的 & 在 bash 中有特殊含义
# ---------------------------------------------------------------------

# env_get KEY：输出值（去掉首尾引号），文件或键不存在时输出空串
env_get() {
    local key=$1 line
    [[ -f $ENV_FILE ]] || return 0
    line=$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)
    line=${line%$'\r'}
    line=${line#*=}
    if [[ ${#line} -ge 2 && ${line:0:1} == '"' && ${line: -1} == '"' ]]; then
        line=${line:1:${#line}-2}
    elif [[ ${#line} -ge 2 && ${line:0:1} == "'" && ${line: -1} == "'" ]]; then
        line=${line:1:${#line}-2}
    fi
    printf '%s' "$line"
}

env_has() {
    [[ -f $ENV_FILE ]] && grep -qE "^[[:space:]]*$1=" "$ENV_FILE"
}

# env_set KEY VALUE：替换已有的行（重复的只保留第一行）或追加到末尾；VALUE 原样写入
env_set() {
    local key=$1 value=$2 tmp
    tmp=$(mktemp "$CONF_DIR/.pestai.env.XXXXXX")
    if env_has "$key"; then
        KEY=$key VALUE=$value awk '
            BEGIN { k = ENVIRON["KEY"]; v = ENVIRON["VALUE"]; pattern = "^[[:space:]]*" k "=" }
            $0 ~ pattern { if (!done) { print k "=" v; done = 1 } next }
            { print }
        ' "$ENV_FILE" >"$tmp"
    else
        cat "$ENV_FILE" >"$tmp"
        printf '%s=%s\n' "$key" "$value" >>"$tmp"
    fi
    chmod 600 "$tmp"
    mv -f "$tmp" "$ENV_FILE"
}

# env_default KEY VALUE：键不存在时才写入（保留用户修改过的值）
env_default() {
    if ! env_has "$1"; then
        env_set "$1" "$2"
    fi
}

# env_secret KEY：值为空时生成 48 位随机十六进制串（只含 0-9a-f，写进 SQL 与配置文件都不需要转义）
env_secret() {
    if [[ -z $(env_get "$1") ]]; then
        env_set "$1" "$(openssl rand -hex 24)"
        ok "已生成 $1"
    fi
}

# ---------------------------------------------------------------------
# MySQL（Ubuntu 的 root 账号默认用 auth_socket：本机 root 用户免密码登录）
# ---------------------------------------------------------------------

mysql_root() {
    mysql --user=root --default-character-set=utf8mb4 "$@"
}

mysql_db() {
    mysql --user=root --default-character-set=utf8mb4 --database="$DB_NAME" "$@"
}

# SQL 单引号字符串转义
sql_quote() {
    local s=$1
    s=${s//\\/\\\\}
    s=${s//\'/\'\'}
    printf '%s' "$s"
}

table_exists() {
    local n
    n=$(mysql_root -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$DB_NAME' AND TABLE_NAME = '$1'") \
        || die "查询数据库失败"
    [[ $n != 0 ]]
}

# ---------------------------------------------------------------------
# 步骤
# ---------------------------------------------------------------------

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --public-url)
                [[ $# -ge 2 ]] || die "--public-url 后面需要填写地址，如 --public-url http://47.100.1.2"
                PUBLIC_URL=$2
                shift 2
                ;;
            --public-url=*)
                PUBLIC_URL=${1#*=}
                shift
                ;;
            --demo)
                DEMO=1
                shift
                ;;
            -h | --help)
                usage
                exit 0
                ;;
            *)
                usage >&2
                die "未知参数：$1"
                ;;
        esac
    done

    if [[ -n $PUBLIC_URL ]]; then
        # 去掉末尾的 /；只接受 http(s)://主机[:端口]
        while [[ $PUBLIC_URL == */ ]]; do
            PUBLIC_URL=${PUBLIC_URL%/}
        done
        if [[ ! $PUBLIC_URL =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]]; then
            die "--public-url 格式不正确：$PUBLIC_URL（应为 http://公网IP，如 http://47.100.1.2）"
        fi
    fi
}

check_environment() {
    [[ $EUID -eq 0 ]] || die "请用 root 运行（非 root 用户请在命令前加 sudo）"

    local os_id="" os_version="" os_name="未知系统"
    if [[ -r /etc/os-release ]]; then
        # shellcheck disable=SC1091
        os_id=$(. /etc/os-release && printf '%s' "${ID:-}")
        os_version=$(. /etc/os-release && printf '%s' "${VERSION_ID:-}")
        os_name=$(. /etc/os-release && printf '%s' "${PRETTY_NAME:-未知系统}")
    fi
    if [[ $os_id != ubuntu || ($os_version != 22.04 && $os_version != 24.04) ]]; then
        die "本脚本只支持 Ubuntu 22.04 / 24.04，当前系统是：$os_name。请在云服务器控制台把系统重装为 Ubuntu 22.04 后重试。"
    fi

    local f
    for f in pestai-server.jar pestai.service nginx-pestai.conf sql/schema.sql sql/seed.sql; do
        [[ -f $STAGE_DIR/$f ]] || die "暂存目录 $STAGE_DIR 中缺少 $f，请在本机重新运行 deploy.ps1 上传"
    done
    if [[ $DEMO -eq 1 && ! -f $STAGE_DIR/sql/seed-dev.sql ]]; then
        die "暂存目录中缺少 sql/seed-dev.sql（--demo 需要）"
    fi
    # 暂存目录里有密钥（.env），只留给当前用户
    chmod -R go-rwx "$STAGE_DIR" 2>/dev/null || true

    if [[ -z $PUBLIC_URL && -z $(env_get PESTAI_PUBLIC_BASE_URL) ]]; then
        die "首次安装必须指定 App 访问的地址：--public-url http://公网IP"
    fi
}

# apt 是否正被占用：apt / apt-get / dpkg 正在运行，或系统自动更新（apt.systemd.daily、unattended-upgrade）正在执行。
# 不能按进程名 unattended-upgr 判断：常驻的 unattended-upgrade-shutdown --wait-for-signal（只等待关机信号、不占用 apt）
# 进程名也被截断成 unattended-upgr，会被误认为一直在更新。
apt_busy() {
    pgrep -x 'apt|apt-get|dpkg' >/dev/null 2>&1 && return 0
    pgrep -f 'apt\.systemd\.daily|bin/unattended-upgrade( |$)' >/dev/null 2>&1
}

# 云服务器刚开机时系统会自动更新软件包并占用 apt 锁，等它结束
wait_apt() {
    local waited=0
    while apt_busy; do
        if [[ $waited -eq 0 ]]; then
            log "系统正在自动更新软件包，等待其结束（最长 10 分钟）"
        fi
        sleep 5
        waited=$((waited + 5))
        if [[ $waited -ge 600 ]]; then
            die "等待 apt 超时：请稍后重新运行，或在服务器上执行 ps aux | grep apt 查看"
        fi
    done
}

install_packages() {
    local pkgs=(openjdk-21-jre-headless mysql-server nginx curl openssl ca-certificates)
    local missing=() p
    for p in "${pkgs[@]}"; do
        if ! dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q 'install ok installed'; then
            missing+=("$p")
        fi
    done
    if [[ ${#missing[@]} -eq 0 ]]; then
        log "软件包已安装（JDK 21、MySQL、Nginx），跳过"
        return
    fi

    log "安装软件包：${missing[*]}（首次安装需要几分钟）"
    export DEBIAN_FRONTEND=noninteractive
    # Ubuntu 22.04 的 needrestart 会弹出「重启哪些服务」对话框，改为自动处理
    export NEEDRESTART_MODE=a
    local apt_opts=(-y -q -o DPkg::Lock::Timeout=600
        -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
    wait_apt
    apt-get "${apt_opts[@]}" update || die "apt-get update 失败：请检查服务器能否访问软件源"
    apt-get "${apt_opts[@]}" install "${missing[@]}" || die "安装软件包失败（见上方输出）"
    ok "软件包安装完成"
}

prepare_user_and_dirs() {
    log "创建系统用户与目录"
    if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
        useradd --system --user-group --home-dir "$APP_DIR" --no-create-home \
            --shell /usr/sbin/nologin "$SERVICE_USER"
        ok "已创建系统用户 $SERVICE_USER（不能登录）"
    fi
    mkdir -p "$APP_DIR" "$FILE_ROOT" "$CONF_DIR"
    chown "$SERVICE_USER:$SERVICE_USER" "$APP_DIR" "$FILE_ROOT"
    chmod 755 "$APP_DIR" "$FILE_ROOT"
    chown root:root "$CONF_DIR"
    chmod 700 "$CONF_DIR"
}

prepare_env_file() {
    log "生成 / 更新服务器配置 $ENV_FILE"
    if [[ ! -f $ENV_FILE ]]; then
        if [[ -f $STAGE_DIR/pestai.env.example ]]; then
            # 模板可能带 Windows 换行，去掉 \r
            tr -d '\r' <"$STAGE_DIR/pestai.env.example" >"$ENV_FILE"
        else
            : >"$ENV_FILE"
        fi
        ok "已根据模板创建 $ENV_FILE"
    fi
    chown root:root "$ENV_FILE"
    chmod 600 "$ENV_FILE"

    if [[ -n $PUBLIC_URL ]]; then
        env_set PESTAI_PUBLIC_BASE_URL "$PUBLIC_URL"
    fi
    PUBLIC_URL=$(env_get PESTAI_PUBLIC_BASE_URL)

    env_default SERVER_ADDRESS 127.0.0.1
    env_default SERVER_PORT 8080
    env_default DB_URL "$DEFAULT_DB_URL"
    env_default DB_USERNAME pestai
    env_secret DB_PASSWORD
    env_secret JWT_SECRET
    # 后台管理（/admin/）：初始管理员 admin 的密码（后端首次启动、t_admin 为空时使用，首次登录后必须修改）；
    # 后台保存的 API Key / AccessKey 的加密主密钥（生成后不要再改，否则后台保存的密钥需要重新填写）
    env_secret PESTAI_ADMIN_INITIAL_PASSWORD
    env_secret PESTAI_CONFIG_SECRET
    env_default PESTAI_FILE_ROOT "$FILE_ROOT"
    env_default JAVA_OPTS "\"$DEFAULT_JAVA_OPTS\""

    local port
    port=$(env_get SERVER_PORT)
    [[ $port =~ ^[0-9]{1,5}$ ]] || die "$ENV_FILE 中的 SERVER_PORT 不是有效端口：$port"
    ok "App 访问地址：$PUBLIC_URL"
}

setup_mysql() {
    log "配置 MySQL"
    systemctl enable -q mysql
    systemctl start mysql
    local i
    for i in $(seq 1 30); do
        if mysqladmin --user=root ping >/dev/null 2>&1; then
            break
        fi
        if [[ $i -eq 30 ]]; then
            die "MySQL 没有启动：请执行 systemctl status mysql 与 journalctl -u mysql -n 50 查看原因"
        fi
        sleep 1
    done
    if ! mysql_root -e 'SELECT 1' >/dev/null 2>&1; then
        die "无法以 root 免密码连接 MySQL（Ubuntu 默认 root 通过 auth_socket 登录）。如果给 MySQL root 设置过密码，请在 /root/.my.cnf 中写入 [client] user=root password=密码 后重试"
    fi

    local db_user db_pass pw
    db_user=$(env_get DB_USERNAME)
    db_pass=$(env_get DB_PASSWORD)
    [[ $db_user =~ ^[A-Za-z0-9_]{1,32}$ ]] || die "$ENV_FILE 中的 DB_USERNAME 只能包含字母、数字、下划线：$db_user"
    [[ $db_user != root ]] || die "DB_USERNAME 不能是 root（会改掉 MySQL root 的登录方式），请改回 pestai"
    [[ -n $db_pass ]] || die "$ENV_FILE 中的 DB_PASSWORD 为空"
    pw=$(sql_quote "$db_pass")

    # 每次都同步一次密码：手动改过 pestai.env 中的 DB_PASSWORD 后重新运行即可生效（密码经标准输入传入，不出现在进程列表）
    # 'localhost' 与 '127.0.0.1' 各建一个账号：JDBC 通过 TCP 连接，开启 skip-name-resolve 时只匹配 IP
    mysql_root <<SQL || die "创建数据库或账号失败"
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS '$db_user'@'localhost' IDENTIFIED BY '$pw';
CREATE USER IF NOT EXISTS '$db_user'@'127.0.0.1' IDENTIFIED BY '$pw';
ALTER USER '$db_user'@'localhost' IDENTIFIED BY '$pw';
ALTER USER '$db_user'@'127.0.0.1' IDENTIFIED BY '$pw';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$db_user'@'localhost';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$db_user'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
    ok "数据库 $DB_NAME 与账号 $db_user 已就绪（只能从本机连接）"
}

ensure_migration_table() {
    mysql_db <<'SQL' || die "创建迁移记录表 t_schema_migration 失败"
CREATE TABLE IF NOT EXISTS t_schema_migration (
    version    VARCHAR(64) NOT NULL COMMENT '迁移脚本文件名，如 001_appeal_new_password.sql',
    applied_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行（或首次安装时登记）时间',
    PRIMARY KEY (version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '已执行的数据库迁移（deploy/install.sh 维护）';
SQL
}

# 迁移脚本的文件名（按文件名排序；只接受字母、数字、点、下划线、短横线）
migration_files() {
    local f name
    for f in "$STAGE_DIR"/sql/migrations/*.sql; do
        name=$(basename "$f")
        if [[ $name =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
            printf '%s\n' "$name"
        else
            warn "跳过文件名不规范的迁移脚本：$name"
        fi
    done | LC_ALL=C sort
}

backup_database() {
    local file
    mkdir -p "$BACKUP_DIR"
    chmod 700 "$BACKUP_DIR"
    file="$BACKUP_DIR/pestai-$(date +%Y%m%d-%H%M%S)-before-migration.sql.gz"
    mysqldump --user=root --default-character-set=utf8mb4 --single-transaction --routines --triggers \
        "$DB_NAME" | gzip >"$file" || die "迁移前备份数据库失败"
    chmod 600 "$file"
    ok "迁移前已备份数据库：$file"
}

migrate_database() {
    local name
    if ! table_exists t_user; then
        # 新数据库：schema.sql 已包含全部迁移的变更，执行后把现有迁移全部登记为已执行
        FRESH_DB=1
        log "新数据库：建表（schema.sql）并写入初始数据（seed.sql）"
        mysql_root <"$STAGE_DIR/sql/schema.sql" || die "执行 schema.sql 失败"
        mysql_root <"$STAGE_DIR/sql/seed.sql" || die "执行 seed.sql 失败"
        ensure_migration_table
        while IFS= read -r name; do
            # </dev/null：不让 mysql 读走循环的标准输入（文件名列表）
            mysql_db -e "INSERT IGNORE INTO t_schema_migration (version, applied_at) VALUES ('$name', NOW())" \
                </dev/null || die "登记迁移 $name 失败"
        done < <(migration_files)
        ok "建表完成，已登记现有迁移脚本为已执行"
    else
        log "已有数据库：检查并执行新的迁移脚本"
        ensure_migration_table
        local applied pending=()
        applied=$(mysql_db -N -B -e 'SELECT version FROM t_schema_migration') || die "读取迁移记录失败"
        while IFS= read -r name; do
            if ! grep -Fxq -- "$name" <<<"$applied"; then
                pending+=("$name")
            fi
        done < <(migration_files)

        if [[ ${#pending[@]} -eq 0 ]]; then
            ok "数据库已是最新，没有需要执行的迁移"
        else
            backup_database
            for name in "${pending[@]}"; do
                log "执行迁移 $name"
                mysql_db <"$STAGE_DIR/sql/migrations/$name" \
                    || die "迁移 $name 执行失败（未登记，修正后重新运行即可；迁移前的备份在 $BACKUP_DIR）"
                mysql_db -e "INSERT INTO t_schema_migration (version, applied_at) VALUES ('$name', NOW())" \
                    || die "登记迁移 $name 失败"
                ok "$name"
            done
        fi
    fi

    if [[ $DEMO -eq 1 ]]; then
        mysql_root <"$STAGE_DIR/sql/seed-dev.sql" || die "导入演示账号失败"
        ok "已导入演示账号 13800005678 / Pest2026demo"
    fi
}

install_app() {
    log "安装后端程序到 $APP_DIR"
    # 先复制成临时文件再改名：正在运行的旧进程不受影响，重启后使用新版本
    install -m 644 -o "$SERVICE_USER" -g "$SERVICE_USER" "$STAGE_DIR/pestai-server.jar" "$APP_DIR/pestai-server.jar.new"
    mv -f "$APP_DIR/pestai-server.jar.new" "$APP_DIR/pestai-server.jar"
    if [[ -f $STAGE_DIR/.env ]]; then
        # 原样复制（Spring Boot 按 ISO-8859-1 读取，中文为 \uXXXX 转义，不能转换编码）
        install -m 600 -o "$SERVICE_USER" -g "$SERVICE_USER" "$STAGE_DIR/.env" "$APP_DIR/.env"
    else
        warn "暂存目录中没有 .env：保留服务器上原有的 $APP_DIR/.env（没有时大模型与短信使用 Mock / 日志实现）"
    fi

    # 后端按 Java 17 编译；系统里装了多个 JDK 且手动选了旧版本时提前报错
    local java_line java_major
    java_line=$(/usr/bin/java -version 2>&1 | head -n 1 || true)
    java_major=$(sed -nE 's/.*version "(1\.)?([0-9]+).*/\2/p' <<<"$java_line")
    if [[ -z $java_major || $java_major -lt 17 ]]; then
        die "/usr/bin/java 版本过低或不可用（${java_line:-未找到}）：请执行 update-alternatives --config java 选择 Java 21 后重试"
    fi

    install -m 644 -o root -g root "$STAGE_DIR/pestai.service" "$SERVICE_FILE"
    systemctl daemon-reload
    systemctl enable -q "$SERVICE"
    START_TIME=$(date '+%Y-%m-%d %H:%M:%S')
    # 启动失败的原因由下面的健康检查输出日志
    systemctl restart "$SERVICE" || warn "systemctl restart $SERVICE 返回失败"
    ok "已重启 $SERVICE 服务（$java_line）"
}

install_nginx() {
    log "配置 Nginx（80 端口 → 后端）"
    local port rendered
    port=$(env_get SERVER_PORT)
    rendered=$(mktemp)
    # 模板中的后端端口为 8080，按 SERVER_PORT 替换
    tr -d '\r' <"$STAGE_DIR/nginx-pestai.conf" | sed "s/127\.0\.0\.1:8080/127.0.0.1:${port}/g" >"$rendered"

    if [[ -f $NGINX_SITE && -f $NGINX_STAMP ]] && ! cmp -s "$NGINX_SITE" "$NGINX_STAMP"; then
        # 上次安装后被手动改过（如配置了 HTTPS），不覆盖
        install -m 644 "$rendered" "$NGINX_SITE.new"
        warn "$NGINX_SITE 被手动修改过，本次没有覆盖；新版本配置保存在 $NGINX_SITE.new，需要时请手动合并"
    else
        install -m 644 "$rendered" "$NGINX_SITE"
        install -m 600 "$rendered" "$NGINX_STAMP"
    fi
    rm -f "$rendered"

    ln -sfn "$NGINX_SITE" /etc/nginx/sites-enabled/pestai
    rm -f /etc/nginx/sites-enabled/default
    nginx -t -q || die "Nginx 配置检查失败（见上方输出）"
    systemctl enable -q nginx
    systemctl reload-or-restart nginx
    ok "Nginx 已生效"
}

open_firewall() {
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
        log "本机防火墙 ufw 已开启：放行 SSH 与 80 端口"
        ufw allow OpenSSH >/dev/null 2>&1 || ufw allow 22/tcp >/dev/null
        ufw allow 80/tcp >/dev/null
        ok "ufw 已放行 OpenSSH、80/tcp"
    fi
}

# 返回 0 表示接口返回了 "code":0
health_ok() {
    local body
    body=$(curl -fsS --max-time 5 "$1" 2>/dev/null) || return 1
    [[ $body =~ \"code\"[[:space:]]*:[[:space:]]*0[,}] ]]
}

wait_healthy() {
    local port url deadline
    port=$(env_get SERVER_PORT)
    url="http://127.0.0.1:${port}${HEALTH_PATH}"
    log "等待后端启动（最长 ${HEALTH_TIMEOUT} 秒）：$url"
    deadline=$((SECONDS + HEALTH_TIMEOUT))
    until health_ok "$url"; do
        if [[ $SECONDS -ge $deadline ]]; then
            printf '\n------ journalctl -u %s -n 80 ------\n' "$SERVICE" >&2
            journalctl -u "$SERVICE" -n 80 --no-pager >&2 || true
            die "后端 ${HEALTH_TIMEOUT} 秒内没有正常响应，原因见上面的日志（常见：数据库连接失败、端口被占用、.env 配置错误）"
        fi
        sleep 3
    done
    ok "后端已正常响应"

    local tries=0
    until health_ok "http://127.0.0.1${HEALTH_PATH}"; do
        tries=$((tries + 1))
        if [[ $tries -ge 10 ]]; then
            die "后端正常，但经 Nginx（80 端口）访问失败：请查看 systemctl status nginx 与 /var/log/nginx/error.log"
        fi
        sleep 1
    done
    ok "Nginx（80 端口）转发正常"
}

print_summary() {
    local modes=""
    if [[ -n $START_TIME ]]; then
        # 启动摘要（StartupReporter，不含密钥）：大模型 / 短信是否为 Mock
        modes=$(journalctl -u "$SERVICE" --since "$START_TIME" --no-pager -o cat 2>/dev/null \
            | grep -E '识图：|问答：|短信：|JWT：' | sed 's/^.*\(识图：\|问答：\|短信：\|JWT：\)/\1/' || true)
    fi

    printf '\n\033[1;32m==================== 部署完成 ====================\033[0m\n'
    cat <<EOF

App 服务器地址：$PUBLIC_URL
  浏览器打开 $PUBLIC_URL$HEALTH_PATH 能看到 "code":0 即表示服务正常
  （打不开时检查云服务器安全组 / 防火墙是否放行 80 端口）
EOF
    if [[ $FRESH_DB -eq 1 ]]; then
        printf '  本次为首次安装：已新建数据库\n'
    fi
    if [[ $DEMO -eq 1 ]]; then
        printf '  演示账号：13800005678 / Pest2026demo\n'
    fi
    printf '\n后台管理：%s/admin/，账号 admin，初始密码 %s（首次登录后必须修改；已修改过请忽略）\n' \
        "$PUBLIC_URL" "$(env_get PESTAI_ADMIN_INITIAL_PASSWORD)"
    if [[ -n $modes ]]; then
        printf '\n后端运行模式：\n'
        printf '%s\n' "$modes" | sed 's/^/  /'
    fi
    cat <<EOF

常用命令：
  systemctl status pestai               查看后端状态
  systemctl restart pestai              重启后端（修改配置后执行）
  journalctl -u pestai -f               实时查看后端日志（Ctrl+C 退出；短信未配置时验证码打印在这里）
  journalctl -u pestai -n 200 --no-pager

配置文件：
  $ENV_FILE        服务器专用配置（数据库密码、公网地址、JVM 参数）
  $APP_DIR/.env              大模型 / 短信密钥（每次部署从本机 server\\.env 覆盖）

备份数据库与图片：
  mysqldump --single-transaction --default-character-set=utf8mb4 pestai | gzip > /root/pestai-\$(date +%F).sql.gz
  tar czf /root/pestai-files-\$(date +%F).tar.gz -C /data/pestai files
EOF
}

main() {
    parse_args "$@"
    check_environment
    install_packages
    prepare_user_and_dirs
    prepare_env_file
    setup_mysql
    migrate_database
    install_app
    install_nginx
    open_firewall
    wait_healthy
    print_summary
}

main "$@"
