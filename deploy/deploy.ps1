<#
.SYNOPSIS
    一键把 pestai-server 部署到云服务器（Ubuntu 22.04 / 24.04）。

.DESCRIPTION
    1. 用 Maven 打包后端（server\target\pestai-server.jar）；
    2. 把 jar、server\.env、SQL 脚本与 deploy\ 下的安装脚本整理到本机临时目录（脚本统一转为 LF 换行）；
    3. 用一次 scp 上传到服务器 /tmp/pestai-deploy，再用一次 ssh 执行 install.sh：安装 JDK 21 / MySQL 8 / Nginx、
       新建数据库或执行新的迁移脚本、安装 systemd 服务并重启、检查服务是否正常，最后删除服务器上的临时目录。
    可重复执行：代码更新后再次运行同一条命令即可。没有配置 SSH 密钥时，上传与安装各需要输入一次服务器密码。
    说明文档：deploy\DEPLOY.md

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Ip 47.100.1.2

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Ip 47.100.1.2 -User ubuntu -Demo

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Ip 47.100.1.2 -PublicUrl https://pest.example.com
#>
[CmdletBinding()]
param(
    # 服务器公网 IP（或已解析到服务器的域名）；不填时运行后询问
    [string]$Ip,
    # SSH 用户；不是 root 时用 sudo 执行安装（腾讯云轻量应用服务器的 Ubuntu 镜像默认用户为 ubuntu）
    [string]$User = 'root',
    # SSH 端口
    [int]$Port = 22,
    # 导入演示账号 13800005678 / Pest2026demo
    [switch]$Demo,
    # 跳过 Maven 打包，直接上传已有的 server\target\pestai-server.jar
    [switch]$SkipBuild,
    # mvn 命令；默认使用 PATH 中的 mvn，找不到时使用 D:\maven\apache-maven-3.9.14\bin\mvn.cmd
    [string]$Mvn,
    # App 访问服务器的完整地址，默认 http://<Ip>；给服务器配置了域名与 HTTPS 后填 https://域名，避免重新部署时被改回 http://IP
    [string]$PublicUrl
)

$ErrorActionPreference = 'Stop'

$RemoteDir = '/tmp/pestai-deploy'
$DeployDir = $PSScriptRoot
$RepoRoot = Split-Path -Parent $DeployDir
$ServerDir = Join-Path $RepoRoot 'server'
$EnvPath = Join-Path $ServerDir '.env'
$JarPath = Join-Path (Join-Path $ServerDir 'target') 'pestai-server.jar'
$SqlDir = Join-Path $ServerDir 'sql'
$FallbackMvn = 'D:\maven\apache-maven-3.9.14\bin\mvn.cmd'

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Done([string]$Message) {
    Write-Host "[完成] $Message" -ForegroundColor Green
}

# 去掉用户误带的 http:// 与末尾的 /
function ConvertTo-ServerHost([string]$Value) {
    if ($null -eq $Value) {
        return ''
    }
    $v = $Value.Trim()
    $v = $v -replace '^[A-Za-z][A-Za-z0-9+.-]*://', ''
    return $v.TrimEnd('/')
}

# IPv4（每段 0–255）或域名
function Test-ServerHost([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    if ($Value -match '^\d{1,3}(\.\d{1,3}){3}$') {
        foreach ($part in $Value.Split('.')) {
            if ([int]$part -gt 255) {
                return $false
            }
        }
        return $true
    }
    if ($Value -match '^[\d.]+$') {
        return $false
    }
    return $Value -match '^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$'
}

function Resolve-Mvn {
    if (-not [string]::IsNullOrWhiteSpace($Mvn)) {
        $cmd = Get-Command $Mvn -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd) {
            return $cmd.Path
        }
        throw "找不到 Maven：$Mvn"
    }
    $cmd = Get-Command 'mvn.cmd' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) {
        return $cmd.Path
    }
    if (Test-Path -LiteralPath $FallbackMvn -PathType Leaf) {
        return $FallbackMvn
    }
    throw "找不到 Maven（mvn）。请安装 Maven 并加入 PATH，或用 -Mvn 指定 mvn.cmd 的完整路径；也可以加 -SkipBuild 上传已有的 server\target\pestai-server.jar"
}

# 复制文本文件并统一为 LF 换行、无 BOM 的 UTF-8（Linux 上的 bash / systemd / nginx / mysql 读取）
function Copy-TextLf([string]$Source, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "找不到文件：$Source"
    }
    $text = [IO.File]::ReadAllText($Source, [Text.Encoding]::UTF8)
    $text = $text.Replace("`r`n", "`n")
    [IO.File]::WriteAllText($Destination, $text, (New-Object Text.UTF8Encoding($false)))
}

$StageRoot = $null
$OriginalOutputEncoding = $null
$ExitCode = 0

try {
    # ---------- 1. 参数与本机环境 ----------
    $ServerHost = ConvertTo-ServerHost $Ip
    if ([string]::IsNullOrWhiteSpace($ServerHost)) {
        while ($true) {
            $ServerHost = ConvertTo-ServerHost (Read-Host '请输入云服务器的公网 IP（如 47.100.1.2）')
            if (Test-ServerHost $ServerHost) {
                break
            }
            Write-Host '格式不正确：只填写 IP（如 47.100.1.2），不要带端口' -ForegroundColor Yellow
        }
    } elseif (-not (Test-ServerHost $ServerHost)) {
        throw "服务器地址格式不正确：$Ip（应为公网 IP，如 47.100.1.2；不要带端口）"
    }
    if ($User -notmatch '^[A-Za-z_][A-Za-z0-9_.-]*$') {
        throw "SSH 用户名格式不正确：$User"
    }
    if ($Port -lt 1 -or $Port -gt 65535) {
        throw "SSH 端口不正确：$Port"
    }
    if ([string]::IsNullOrWhiteSpace($PublicUrl)) {
        $AppUrl = "http://$ServerHost"
    } else {
        $AppUrl = $PublicUrl.Trim().TrimEnd('/')
        if ($AppUrl -notmatch '^https?://[A-Za-z0-9.-]+(:\d{1,5})?$') {
            throw "-PublicUrl 格式不正确：$PublicUrl（应为 http://IP 或 https://域名，不带路径）"
        }
    }
    # App「关于」页中填写的地址：http://IP 只需填 IP
    $AppInput = $AppUrl
    if ($AppUrl -eq "http://$ServerHost") {
        $AppInput = $ServerHost
    }
    $Target = "$User@$ServerHost"

    foreach ($tool in 'ssh', 'scp') {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "找不到 $tool 命令：请在 Windows「设置 → 系统 → 可选功能 → 添加可选功能」中安装「OpenSSH 客户端」，然后重新打开 PowerShell"
        }
    }
    # 只检查是否存在，不读取内容（其中是大模型 / 短信密钥，原样上传）
    if (-not (Test-Path -LiteralPath $EnvPath -PathType Leaf)) {
        throw "找不到 server\.env。请先把 server\.env.example 复制为 server\.env 并填写密钥（说明见 server\README.md）；暂时不需要大模型 / 短信时也要创建一个空的 server\.env"
    }

    Write-Host "服务器：$Target（SSH 端口 $Port）  App 地址：$AppUrl" -ForegroundColor Cyan

    # ---------- 2. 打包后端 ----------
    if ($SkipBuild) {
        Write-Step '跳过打包（-SkipBuild），使用已有的 server\target\pestai-server.jar'
    } else {
        $MvnPath = Resolve-Mvn
        Write-Step "打包后端：mvn -q clean package -DskipTests（$MvnPath）"
        Push-Location -LiteralPath $ServerDir
        try {
            & $MvnPath -q clean package -DskipTests
            $BuildCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        if ($BuildCode -ne 0) {
            throw "Maven 打包失败（退出码 $BuildCode）。如果本机正在运行 server\target\pestai-server.jar，请先停止它（clean 需要删除该文件）；也可以加 -SkipBuild 上传已有的 jar"
        }
        Write-Done '打包完成'
    }
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
        throw "找不到 $JarPath：请去掉 -SkipBuild 重新打包"
    }

    # ---------- 3. 整理上传文件 ----------
    Write-Step '整理上传文件'
    $StageRoot = Join-Path ([IO.Path]::GetTempPath()) ('pestai-deploy-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $Stage = Join-Path $StageRoot 'pestai-deploy'
    $StageSql = Join-Path $Stage 'sql'
    $StageMigrations = Join-Path $StageSql 'migrations'
    New-Item -ItemType Directory -Path $StageMigrations -Force | Out-Null

    Copy-Item -LiteralPath $JarPath -Destination (Join-Path $Stage 'pestai-server.jar')
    # .env 按字节原样复制（Spring Boot 按 ISO-8859-1 读取，中文为 \uXXXX 转义，不能转换编码）
    Copy-Item -LiteralPath $EnvPath -Destination (Join-Path $Stage '.env')
    foreach ($name in 'install.sh', 'pestai.service', 'nginx-pestai.conf', 'pestai.env.example') {
        Copy-TextLf (Join-Path $DeployDir $name) (Join-Path $Stage $name)
    }
    foreach ($name in 'schema.sql', 'seed.sql', 'seed-dev.sql') {
        Copy-TextLf (Join-Path $SqlDir $name) (Join-Path $StageSql $name)
    }
    $Migrations = @(Get-ChildItem -LiteralPath (Join-Path $SqlDir 'migrations') -Filter '*.sql' -File | Sort-Object Name)
    foreach ($file in $Migrations) {
        Copy-TextLf $file.FullName (Join-Path $StageMigrations $file.Name)
    }
    $JarMb = [math]::Round((Get-Item -LiteralPath $JarPath).Length / 1MB, 1)
    Write-Done "后端 jar（$JarMb MB）、.env、安装脚本、SQL 脚本（迁移 $($Migrations.Count) 个）"

    # ---------- 4. 上传 ----------
    $SshOptions = @('-o', 'ServerAliveInterval=30', '-o', 'StrictHostKeyChecking=accept-new')
    Write-Step "上传到 ${Target}:$RemoteDir（没有配置 SSH 密钥时请输入服务器密码，输入时不显示）"
    Push-Location -LiteralPath $StageRoot
    try {
        & scp @SshOptions -P $Port -r 'pestai-deploy' "${Target}:/tmp/"
        $ScpCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($ScpCode -ne 0) {
        throw "上传中断（网络不稳定或连接被重置）：直接重新运行同一条命令即可；如果一开始就连不上，再检查 IP、用户名、密码，以及云服务器安全组是否放行了 SSH 端口 $Port"
    }
    Write-Done '上传完成'

    # ---------- 5. 在服务器上安装 ----------
    $InstallArgs = "--public-url $AppUrl"
    if ($Demo) {
        $InstallArgs += ' --demo'
    }
    $Sudo = ''
    if ($User -ne 'root') {
        $Sudo = 'sudo '
    }
    # 无论安装成功与否都删除服务器上的临时目录（其中有 .env 密钥），并返回安装脚本的退出码
    $RemoteCommand = "${Sudo}bash $RemoteDir/install.sh $InstallArgs; rc=`$?; rm -rf $RemoteDir; exit `$rc"
    Write-Step '在服务器上安装（首次需要几分钟：安装 JDK 21、MySQL、Nginx）'
    try {
        # 服务器输出的中文是 UTF-8
        $OriginalOutputEncoding = [Console]::OutputEncoding
        [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
    } catch {
        $OriginalOutputEncoding = $null
    }
    & ssh @SshOptions -t -p $Port $Target $RemoteCommand
    $SshCode = $LASTEXITCODE
    if ($SshCode -ne 0) {
        throw "服务器上的安装没有完成（退出码 $SshCode），原因见上方输出。修正后重新运行同一条命令即可"
    }

    Write-Host ''
    Write-Host '==================== 部署完成 ====================' -ForegroundColor Green
    Write-Host "App 服务器地址：$AppUrl"
    Write-Host "验证：浏览器打开 $AppUrl/pestai/v1/app/version，看到 `"code`":0 即表示服务正常"
    Write-Host ''
    Write-Host '让 App 连接这台服务器（二选一）：'
    Write-Host "  1. 重新打包 App：在项目根目录的 local.properties 中写入 BASE_URL=$AppUrl，然后重新构建并安装 App"
    Write-Host "  2. 不重新打包：在 App 中打开「我的 → 关于」，连续点击版本号 5 次，在「服务器地址」中填写 $AppInput 后保存"
    Write-Host ''
    Write-Host '更新后端：修改代码后重新运行同一条命令（自动打包、上传、执行新的数据库迁移并重启）'
} catch {
    Write-Host ''
    Write-Host "[失败] $($_.Exception.Message)" -ForegroundColor Red
    $ExitCode = 1
} finally {
    if ($null -ne $OriginalOutputEncoding) {
        try {
            [Console]::OutputEncoding = $OriginalOutputEncoding
        } catch {
        }
    }
    # 本机临时目录中有 .env 副本，一律删除
    if ($StageRoot -and (Test-Path -LiteralPath $StageRoot)) {
        Remove-Item -LiteralPath $StageRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

exit $ExitCode
