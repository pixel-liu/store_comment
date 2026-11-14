param(
    [Parameter(Mandatory)][ValidateSet('Start', 'Stop')][string]$Action,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $project '.local-services'
$state = Join-Path $runtime 'launcher'
$mysqlBin = 'D:\soft\MySQL\MySQL Server 8.0\bin'
$redisBin = 'D:\soft\AnotherRedis'
$mysqlData = Join-Path $runtime 'mysql\data'
$redisDir = Join-Path $runtime 'redis'
$redisConfig = Join-Path $redisDir 'redis.local.conf'
$jar = Join-Path $project 'target\foodiego-0.0.1-SNAPSHOT.jar'
$rabbitPort = 5672
$baseUrl = 'http://127.0.0.1:8081'
$services = [ordered]@{
    MySQL = 3307
    Redis = 6379
    FoodieGo = 8081
}
$stopOrder = @('FoodieGo', 'Redis', 'MySQL')
$started = [System.Collections.Generic.List[string]]::new()

function Get-PortOwners([string]$Name) {
    Get-NetTCPConnection -LocalPort $services[$Name] -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
}

function Get-TrackedProcess([string]$Name) {
    $file = Join-Path $state "$Name.json"
    if (Test-Path -LiteralPath $file) {
        $record = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        $process = Get-Process -Id $record.Id -ErrorAction SilentlyContinue
        if ($process -and -not $process.HasExited -and $process.StartTime.ToFileTimeUtc().ToString() -eq $record.StartTime) {
            return $process
        }
    }
}

function Wait-ForListener([string]$Name, [int]$ExpectedId, [switch]$AllowChild) {
    $deadline = (Get-Date).AddSeconds(45)
    do {
        foreach ($owner in @(Get-PortOwners $Name)) {
            $ancestor = $owner
            while ($AllowChild -and $ancestor -and $ancestor -ne $ExpectedId) {
                $details = Get-CimInstance Win32_Process -Filter "ProcessId=$ancestor"
                $ancestor = $details.ParentProcessId
            }
            if ($ancestor -ne $ExpectedId) {
                throw "Port $($services[$Name]) is occupied by PID $owner."
            }
            return Get-Process -Id $owner
        }
        Start-Sleep -Milliseconds 350
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not listen on port $($services[$Name]). See $state."
}

function Start-Tracked([string]$Name, [string]$Exe, [string[]]$Arguments) {
    $tracked = Get-TrackedProcess $Name
    if ($tracked) {
        $null = Wait-ForListener $Name $tracked.Id
        return
    }
    Write-Host "Starting $Name..."
    $launcher = Start-Process -FilePath $Exe -ArgumentList $Arguments -WorkingDirectory $project `
        -RedirectStandardOutput (Join-Path $state "$Name.out.log") `
        -RedirectStandardError (Join-Path $state "$Name.err.log") -WindowStyle Hidden -PassThru
    try {
        $process = Wait-ForListener $Name $launcher.Id -AllowChild
        @{ Id = $process.Id; StartTime = $process.StartTime.ToFileTimeUtc().ToString() } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $state "$Name.json") -Encoding ascii
        $started.Add($Name)
    } catch {
        if (-not $launcher.HasExited) {
            & taskkill.exe /PID $launcher.Id /T /F | Out-Null
        }
        throw
    }
}

function Stop-Tracked([string]$Name) {
    $process = Get-TrackedProcess $Name
    if ($process) {
        Write-Host "Stopping $Name..."
        $owners = @(Get-PortOwners $Name)
        if ($process.Id -in $owners) {
            switch ($Name) {
                'MySQL' { & (Join-Path $mysqlBin 'mysqladmin.exe') -h 127.0.0.1 -P 3307 -uroot shutdown }
                'Redis' { & (Join-Path $redisBin 'redis-cli.exe') -p 6379 -a $redisPassword shutdown save }
                'FoodieGo' { Stop-Process -InputObject $process }
            }
            if ($Name -ne 'FoodieGo' -and $LASTEXITCODE -ne 0) {
                throw "$Name shutdown failed (exit $LASTEXITCODE)."
            }
        } else {
            Stop-Process -InputObject $process
        }
        if (-not $process.WaitForExit(15000)) { throw "$Name is still running." }
    }
    $file = Join-Path $state "$Name.json"
    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
}

function Wait-ForHealth {
    $deadline = (Get-Date).AddSeconds(35)
    do {
        try {
            $health = Invoke-RestMethod "$baseUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 450
    } while ((Get-Date) -lt $deadline)
    throw "FoodieGo did not become healthy. See $state\FoodieGo.out.log and FoodieGo.err.log."
}

function Enter-OperationLock {
    $lockFile = Join-Path $state 'operation.lock'
    $deadline = (Get-Date).AddSeconds(60)
    do {
        try {
            return [System.IO.File]::Open($lockFile, 'OpenOrCreate', 'ReadWrite', 'None')
        } catch [System.IO.IOException] {
            Start-Sleep -Milliseconds 300
        }
    } while ((Get-Date) -lt $deadline)
    throw 'Another FoodieGo start or stop operation is still running. Wait for it to finish, then try again.'
}

function Start-Project {
    foreach ($name in $services.Keys) {
        $tracked = Get-TrackedProcess $name
        foreach ($owner in @(Get-PortOwners $name)) {
            if (-not $tracked -or $tracked.Id -ne $owner) {
                throw "Port $($services[$name]) is occupied by PID $owner."
            }
        }
    }
    $rabbitService = Get-Service -Name 'RabbitMQ' -ErrorAction SilentlyContinue
    if (-not $rabbitService -or $rabbitService.Status -ne 'Running') {
        throw 'The shared RabbitMQ Windows service is not running. Start RabbitMQ in Windows Services, then run start.bat again.'
    }
    if (-not (Test-NetConnection -ComputerName $env:RABBITMQ_HOST -Port $rabbitPort -InformationLevel Quiet -WarningAction SilentlyContinue)) {
        throw "Cannot reach the RabbitMQ Windows service at $($env:RABBITMQ_HOST):$rabbitPort. Check its listener configuration."
    }
    Write-Host "Using the RabbitMQ Windows service at $($env:RABBITMQ_HOST):$rabbitPort."
    if (-not (Test-Path -LiteralPath (Join-Path $mysqlData 'mysql'))) {
        throw "Project MySQL is not initialized at $mysqlData. See README.md."
    }

    if (-not (Get-TrackedProcess 'FoodieGo')) {
        $jarFile = Get-Item -LiteralPath $jar -ErrorAction SilentlyContinue
        $inputs = @(Get-Item pom.xml) + @(Get-ChildItem src/main,.mvn -Recurse -File)
        if (-not $jarFile -or ($inputs | Where-Object LastWriteTimeUtc -GT $jarFile.LastWriteTimeUtc)) {
            Write-Host 'Building FoodieGo...'
            & mvn.cmd -q -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw "Maven failed (exit $LASTEXITCODE)." }
        }
    }

    New-Item -ItemType Directory -Force -Path $redisDir | Out-Null
    if (-not (Test-Path -LiteralPath $redisConfig)) {
        @(
            'bind 127.0.0.1'
            'port 6379'
            "requirepass $redisPassword"
            ('dir "{0}"' -f ($redisDir -replace '\\', '/'))
            'dbfilename dump.rdb'
            'save ""'
        ) | Set-Content -LiteralPath $redisConfig -Encoding ascii
    }

    Start-Tracked 'MySQL' (Join-Path $mysqlBin 'mysqld.exe') @('--no-defaults', ('"--datadir={0}"' -f $mysqlData),
        '--port=3307', '--bind-address=127.0.0.1', '--mysqlx=OFF')
    Start-Tracked 'Redis' (Join-Path $redisBin 'redis-server.exe') @('"{0}"' -f $redisConfig)
    Start-Tracked 'FoodieGo' (Get-Command java.exe).Source @('-jar', ('"{0}"' -f $jar))
    Wait-ForHealth
    Write-Host "FoodieGo is ready at $baseUrl/#/meals"
    if (-not $NoBrowser) { Start-Process "$baseUrl/#/meals" }
}

$redisPassword = '123456'
$env:MYSQL_PWD = '123456'
$env:MYSQL_HOST = '127.0.0.1'
$env:MYSQL_PORT = '3307'
$env:MYSQL_USER = 'root'
$env:MYSQL_PASSWORD = $env:MYSQL_PWD
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = $redisPassword
$env:RABBITMQ_HOST = '127.0.0.1'
$env:RABBITMQ_PORT = [string]$rabbitPort
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8081'
$env:SPRING_PROFILES_ACTIVE = 'dev'

$localAdminFile = Join-Path $state 'admin-user-ids.txt'
if ($Action -eq 'Start' -and -not $env:FOODIEGO_ADMIN_USER_IDS -and (Test-Path -LiteralPath $localAdminFile)) {
    $localAdminIds = (Get-Content -LiteralPath $localAdminFile -Raw).Trim()
    if ($localAdminIds -notmatch '^[1-9]\d*(\s*,\s*[1-9]\d*)*$') {
        throw 'Invalid administrator IDs in the local launcher configuration.'
    }
    $env:FOODIEGO_ADMIN_USER_IDS = $localAdminIds
}

New-Item -ItemType Directory -Force -Path $state | Out-Null
Push-Location $project
try {
    $lock = Enter-OperationLock
    try {
        if ($Action -eq 'Start') { Start-Project; return }
        $failed = $false
        foreach ($name in $stopOrder) {
            try { Stop-Tracked $name } catch { Write-Warning $_.Exception.Message; $failed = $true }
        }
        if ($failed) { throw 'Some FoodieGo processes could not be stopped.' }
        Write-Host 'FoodieGo is stopped.'
    } catch {
        foreach ($name in $stopOrder) {
            if ($name -in $started) {
                try { Stop-Tracked $name } catch { Write-Warning $_.Exception.Message }
            }
        }
        throw
    } finally {
        $lock.Dispose()
    }
} finally {
    Pop-Location
}
