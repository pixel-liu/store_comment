[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Stop-TrackedProcess {
    param(
        [string]$PidFile,
        [string]$ExpectedPath,
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $PidFile)) {
        Write-Host "$Name 没有本脚本记录的运行进程，跳过。"
        return
    }

    $processId = [int](Get-Content -LiteralPath $PidFile -Raw)
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process -and $process.Path -eq $ExpectedPath) {
        Stop-Process -Id $processId -Force
        Write-Host "$Name 已停止。"
    } else {
        Write-Host "$Name 的记录进程不存在或路径不匹配，未停止其他进程。"
    }
    Remove-Item -LiteralPath $PidFile -Force
}

if (-not (Test-Administrator)) {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath)
    Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs
    exit
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$pidRoot = Join-Path $projectRoot '.local-services\pids'

Stop-TrackedProcess -PidFile (Join-Path $pidRoot 'redis.pid') -ExpectedPath 'D:\soft\AnotherRedis\redis-server.exe' -Name 'Redis'
Stop-TrackedProcess -PidFile (Join-Path $pidRoot 'mysql.pid') -ExpectedPath 'D:\soft\MySQL\MySQL Server 8.0\bin\mysqld.exe' -Name 'MySQL'

$rabbit = Get-Service -Name 'RabbitMQ' -ErrorAction Stop
if ($rabbit.Status -eq 'Running') {
    Stop-Service -Name 'RabbitMQ' -Force
    Write-Host 'RabbitMQ 已停止。'
} else {
    Write-Host 'RabbitMQ 已经停止。'
}

Write-Host ''
Write-Host '依赖服务已关闭。此脚本不会停止 Spring Boot 后端。'
