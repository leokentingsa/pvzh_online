#requires -Version 5.1
$ErrorActionPreference = "Stop"

# PVZH Online 0.4.0 - Windows + Tailscale Funnel launcher
# Keep this file in the project root beside the "server" folder.

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ServerDir  = Join-Path $ProjectDir "server"
$ServerPy   = Join-Path $ServerDir "server.py"
$Requirements = Join-Path $ServerDir "requirements.txt"
$CertDir    = Join-Path $ServerDir "certs"
$Domain     = "your-node.tailnet-name.ts.net"
$CertFile   = Join-Path $CertDir "relay.crt"
$KeyFile    = Join-Path $CertDir "relay.key"
$PidFile    = Join-Path $ServerDir ".pvzh_server.pid"
$StdoutLog  = Join-Path $ServerDir "server.stdout.log"
$StderrLog  = Join-Path $ServerDir "server.stderr.log"

if ($Domain -eq "your-node.tailnet-name.ts.net") {
    Write-Host "请先把本脚本第 12 行的 `$Domain 改成你自己的 Tailscale 节点域名。" -ForegroundColor Yellow
    Write-Host "节点域名可以用 'tailscale status' 第一列后面的名字查到（形如 xxx.tailXXXX.ts.net）。" -ForegroundColor Yellow
    throw "未配置域名，脚本中止。"
}

function Write-Step([string]$Message) {
    Write-Host "`n==== $Message ====" -ForegroundColor Cyan
}

# Funnel port forwarding on Windows should be configured from an elevated shell.
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "需要管理员权限，正在请求 UAC 授权……" -ForegroundColor Yellow
    $args = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $args
    exit
}

if (-not (Test-Path $ServerPy)) {
    throw "找不到 $ServerPy。请把本脚本放在 PVZH_Online_Integrated_0.4.0 根目录。"
}
if (-not (Test-Path $Requirements)) {
    throw "找不到 server\requirements.txt。"
}
if (-not (Get-Command tailscale -ErrorAction SilentlyContinue)) {
    throw "找不到 tailscale 命令。请先安装并登录 Tailscale。"
}

Write-Step "检查 Tailscale"
$tsStatus = (& tailscale status 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Tailscale 当前不可用：`n$tsStatus" }
Write-Host $tsStatus

Write-Step "准备 Python 环境"
$VenvPython = Join-Path $ProjectDir ".venv\Scripts\python.exe"
if (-not (Test-Path $VenvPython)) {
    py -3.13 -m venv (Join-Path $ProjectDir ".venv")
    if ($LASTEXITCODE -ne 0) { throw "创建 Python 虚拟环境失败。" }
}
& $VenvPython -m pip install --disable-pip-version-check -r $Requirements
if ($LASTEXITCODE -ne 0) { throw "安装 server 依赖失败。" }

Write-Step "准备 Relay TLS 证书"
New-Item -ItemType Directory -Force -Path $CertDir | Out-Null
# Renew only when the remaining validity is below 7 days; do not request a new cert on every launch.
& tailscale cert "--min-validity=168h" "--cert-file=$CertFile" "--key-file=$KeyFile" $Domain
if ($LASTEXITCODE -ne 0) {
    throw "申请/续期 Tailscale 域名证书失败。确认 HTTPS Certificates 已启用，并检查域名是否仍为 $Domain。"
}
if (-not (Test-Path $CertFile) -or -not (Test-Path $KeyFile)) {
    throw "证书文件没有生成：$CertFile / $KeyFile"
}

Write-Step "配置本机服务（不直接暴露本地端口）"
# API: Funnel terminates public HTTPS on 443 and forwards HTTP to loopback:8780.
$env:PVZH_API_HOST = "127.0.0.1"
$env:PVZH_API_PORT = "8780"
$env:PVZH_API_TLS_CERT = ""
$env:PVZH_API_TLS_KEY = ""

# Relay: raw TCP Funnel forwards to loopback:8768; Relay itself terminates TLS.
$env:PVZH_RELAY_BIND_HOST = "127.0.0.1"
$env:PVZH_RELAY_PORT = "8768"
$env:PVZH_RELAY_TLS_CERT = $CertFile
$env:PVZH_RELAY_TLS_KEY = $KeyFile
$env:PVZH_PUBLIC_RELAY_HOST = $Domain
$env:PVZH_PUBLIC_RELAY_PORT = "8443"
$env:PVZH_PUBLIC_RELAY_TLS = "true"

# Keep conservative room limits from the supplied .env.example.
$env:PVZH_ROOM_TTL_SECONDS = "7200"
$env:PVZH_MAX_ROOMS = "5000"

Write-Step "启动 Room API + Relay"
$existingHealthy = $false
try {
    $null = Invoke-RestMethod -Uri "http://127.0.0.1:8780/health" -TimeoutSec 2
    $existingHealthy = $true
    Write-Host "检测到本机 8780 已有健康 API，复用现有服务。" -ForegroundColor Green
} catch {}

if (-not $existingHealthy) {
    if (Test-Path $PidFile) {
        try {
            $oldPid = [int](Get-Content $PidFile -Raw)
            Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
        } catch {}
        Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    }

    $proc = Start-Process -FilePath $VenvPython -ArgumentList @("server.py") `
        -WorkingDirectory $ServerDir -RedirectStandardOutput $StdoutLog `
        -RedirectStandardError $StderrLog -PassThru
    Set-Content -Path $PidFile -Value $proc.Id -Encoding ASCII

    $healthy = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try {
            $null = Invoke-RestMethod -Uri "http://127.0.0.1:8780/health" -TimeoutSec 2
            $healthy = $true
            break
        } catch {
            if ($proc.HasExited) { break }
        }
    }
    if (-not $healthy) {
        Write-Host "`n服务器未通过 /health 检查。最近日志：" -ForegroundColor Red
        if (Test-Path $StdoutLog) { Get-Content $StdoutLog -Tail 50 }
        if (Test-Path $StderrLog) { Get-Content $StderrLog -Tail 50 }
        throw "Room API 未能在 127.0.0.1:8780 启动。Funnel 尚未创建。"
    }
}

Write-Step "检查 Relay 本地监听"
$relayReady = $false
for ($i = 0; $i -lt 10; $i++) {
    $tcp = New-Object Net.Sockets.TcpClient
    try {
        $task = $tcp.ConnectAsync("127.0.0.1", 8768)
        if ($task.Wait(1000) -and $tcp.Connected) { $relayReady = $true; break }
    } catch {} finally { $tcp.Dispose() }
    Start-Sleep -Milliseconds 500
}
if (-not $relayReady) {
    Write-Host "Relay 8768 未能建立 TCP 连接。服务器日志：" -ForegroundColor Red
    if (Test-Path $StdoutLog) { Get-Content $StdoutLog -Tail 50 }
    if (Test-Path $StderrLog) { Get-Content $StderrLog -Tail 50 }
    throw "Relay 未启动；为避免公开空端口，暂不创建 Funnel。"
}

Write-Step "建立公网入口"
# HTTPS Funnel terminates HTTPS at Tailscale and proxies HTTP to local API.
& tailscale funnel --bg --https=443 "http://127.0.0.1:8780"
if ($LASTEXITCODE -ne 0) { throw "API Funnel 配置失败。" }

# Raw TCP forwarding: TLS remains end-to-end between the Android app and local Relay.
& tailscale funnel --bg --tcp=8443 "tcp://127.0.0.1:8768"
if ($LASTEXITCODE -ne 0) { throw "Relay TCP Funnel 配置失败。API Funnel 已配置，可用停止脚本撤销。" }

Write-Step "完成"
Write-Host "房间 API：" -ForegroundColor Green
Write-Host "  https://$Domain/"
Write-Host "Relay：" -ForegroundColor Green
Write-Host "  $Domain`:8443 (TLS)"
Write-Host "`nFunnel 状态：" -ForegroundColor Cyan
& tailscale funnel status
Write-Host "`n服务器日志：" -ForegroundColor Cyan
Write-Host "  $StdoutLog"
Write-Host "  $StderrLog"
Write-Host "`n停止服务请运行 Stop-PVZH-Online.ps1。"
