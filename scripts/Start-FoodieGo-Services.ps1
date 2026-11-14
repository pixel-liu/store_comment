[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-LocalPort {
    param([int]$Port)

    return Test-NetConnection -ComputerName '127.0.0.1' -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
}

function Wait-ForLocalPort {
    param(
        [int]$Port,
        [string]$Name,
        [int]$TimeoutSeconds = 20
    )

    for ($attempt = 0; $attempt -lt $TimeoutSeconds; $attempt += 1) {
        if (Test-LocalPort -Port $Port) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name 未能在 $TimeoutSeconds 秒内监听端口 $Port。"
}

function Write-PidFile {
    param(
        [string]$Path,
        [int]$ProcessId
    )

    Set-Content -LiteralPath $Path -Value $ProcessId -Encoding ascii -NoNewline -Force
}

if (-not (Test-Administrator)) {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath)
    Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs
    exit
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.local-services'
$mysqlData = Join-Path $runtimeRoot 'mysql\data'
$redisRoot = Join-Path $runtimeRoot 'redis'
$pidRoot = Join-Path $runtimeRoot 'pids'
$mysqlPidPath = Join-Path $pidRoot 'mysql.pid'
$redisPidPath = Join-Path $pidRoot 'redis.pid'
$mysqlLog = Join-Path $runtimeRoot 'mysql\mysql-error.log'
$redisConfig = Join-Path $redisRoot 'redis.local.conf'

$mysqlExe = 'D:\soft\MySQL\MySQL Server 8.0\bin\mysqld.exe'
$redisExe = 'D:\soft\AnotherRedis\redis-server.exe'

foreach ($requiredPath in @($mysqlExe, $redisExe)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "找不到运行依赖：$requiredPath"
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $mysqlData 'mysql'))) {
    throw "未找到已初始化的项目 MySQL 数据目录：$mysqlData"
}

New-Item -ItemType Directory -Force -Path $redisRoot, $pidRoot | Out-Null
@(
    'bind 127.0.0.1'
    'port 6379'
    'requirepass 123456'
    "dir $($redisRoot -replace '\\', '/')"
    'dbfilename dump.rdb'
    'save ""'
    'appendonly no'
    'stop-writes-on-bgsave-error no'
    "logfile $((Join-Path $redisRoot 'redis.log') -replace '\\', '/')"
) | Set-Content -LiteralPath $redisConfig -Encoding ascii

if (Test-LocalPort -Port 3306) {
    Write-Host 'MySQL 已在 3306 端口运行。'
} else {
    $mysql = Start-Process -FilePath $mysqlExe -ArgumentList @(
        '--no-defaults',
        "--datadir=$mysqlData",
        '--port=3306',
        '--bind-address=127.0.0.1',
        "--log-error=$mysqlLog"
    ) -WindowStyle Hidden -PassThru
    Write-PidFile -Path $mysqlPidPath -ProcessId $mysql.Id
    Wait-ForLocalPort -Port 3306 -Name 'MySQL'
    Write-Host 'MySQL 已启动。'
}

if (Test-LocalPort -Port 6379) {
    Write-Host 'Redis 已在 6379 端口运行。'
} else {
    $redis = Start-Process -FilePath $redisExe -ArgumentList $redisConfig -WorkingDirectory $redisRoot -WindowStyle Hidden -PassThru
    Write-PidFile -Path $redisPidPath -ProcessId $redis.Id
    Wait-ForLocalPort -Port 6379 -Name 'Redis'
    Write-Host 'Redis 已启动。'
}

$rabbit = Get-Service -Name 'RabbitMQ' -ErrorAction Stop
if ($rabbit.Status -ne 'Running') {
    Start-Service -Name 'RabbitMQ'
    Wait-ForLocalPort -Port 5672 -Name 'RabbitMQ'
    Write-Host 'RabbitMQ 已启动。'
} else {
    Write-Host 'RabbitMQ 已在运行。'
}

Write-Host ''
Write-Host '依赖服务已就绪。请自行启动 Spring Boot 后端。'
