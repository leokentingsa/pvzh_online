#requires -Version 5.1
$ErrorActionPreference = "Continue"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ServerDir = Join-Path $ProjectDir "server"
$PidFile = Join-Path $ServerDir ".pvzh_server.pid"

Write-Host "正在关闭 PVZH Online 公网入口……" -ForegroundColor Cyan
tailscale funnel reset

if (Test-Path $PidFile) {
    try {
        $pidValue = [int](Get-Content $PidFile -Raw)
        Stop-Process -Id $pidValue -Force -ErrorAction Stop
        Write-Host "已停止服务器进程 PID $pidValue。" -ForegroundColor Green
    } catch {
        Write-Host "服务器进程可能已经停止。" -ForegroundColor Yellow
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "未找到本启动脚本记录的服务器 PID；如果服务是手动启动的，请单独关闭它。" -ForegroundColor Yellow
}
Write-Host "停止操作完成。"
