param(
    [string]$JarPath = (Join-Path $PSScriptRoot '..\backend\spring\target\ds-agent-server-0.0.1-SNAPSHOT.jar')
)

$ErrorActionPreference = 'Continue'

function New-RandomSecret([int]$Length) {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Invoke-Docker([string[]]$Arguments) {
    $output = & docker @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Docker command failed during the disposable MySQL rehearsal' }
    return ($output -join "`n").Trim()
}

if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "Spring executable JAR is missing: $JarPath"
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java is required' }

$container = 'structify-mysql-flyway-' + ([guid]::NewGuid().ToString('N').Substring(0, 10))
$database = 'structify_rehearsal'
$dbUser = 'structify_app'
$rootPassword = New-RandomSecret 48
$dbPassword = New-RandomSecret 48
$jwtSecret = New-RandomSecret 96
$nodeCompatSecret = New-RandomSecret 96
$mysqlPort = Get-FreeTcpPort
$serverPort = Get-FreeTcpPort
$stdoutPath = [IO.Path]::GetTempFileName()
$stderrPath = [IO.Path]::GetTempFileName()
$process = $null
$oldEnvironment = @{}
$environmentKeys = @(
    'SPRING_PROFILES_ACTIVE', 'SERVER_ADDRESS', 'SERVER_PORT', 'DB_URL', 'DB_USER', 'DB_PASSWORD',
    'JWT_SECRET', 'NODE_COMPAT_ENABLED', 'NODE_COMPAT_JWT_SECRET', 'CORS_ALLOWED_ORIGINS',
    'AUTH_COOKIE_SECURE', 'AUTH_MAIL_ENABLED', 'AUTH_EXPOSE_DEV_CODE', 'MODEL_API_KEY',
    'PISTON_BASE_URL', 'KNOWLEDGE_DIR', 'RESOURCE_DIR'
)

try {
    Invoke-Docker @(
        'run', '-d', '--name', $container, '--label', 'com.structify.rehearsal=true',
        '-e', "MYSQL_DATABASE=$database", '-e', "MYSQL_USER=$dbUser",
        '-e', "MYSQL_PASSWORD=$dbPassword", '-e', "MYSQL_ROOT_PASSWORD=$rootPassword",
        '-p', "127.0.0.1:${mysqlPort}:3306", 'mysql:8.4'
    ) | Out-Null

    $mysqlReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec -e "MYSQL_PWD=$rootPassword" $container mysqladmin ping -h 127.0.0.1 -uroot --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $mysqlReady = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $mysqlReady) { throw 'MySQL did not become ready' }

    $knowledgeDir = Join-Path $env:TEMP 'structify-empty-knowledge'
    $resourceDir = Join-Path $env:TEMP 'structify-empty-resources'
    New-Item -ItemType Directory -Force -Path $knowledgeDir, $resourceDir | Out-Null
    $values = @{
        SPRING_PROFILES_ACTIVE = 'prod'
        SERVER_ADDRESS = '127.0.0.1'
        SERVER_PORT = [string]$serverPort
        DB_URL = "jdbc:mysql://127.0.0.1:${mysqlPort}/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=true&requireSSL=true"
        DB_USER = $dbUser
        DB_PASSWORD = $dbPassword
        JWT_SECRET = $jwtSecret
        NODE_COMPAT_ENABLED = 'true'
        NODE_COMPAT_JWT_SECRET = $nodeCompatSecret
    CORS_ALLOWED_ORIGINS = 'https://structify.cn,https://admin.structify.cn'
        AUTH_COOKIE_SECURE = 'true'
        AUTH_MAIL_ENABLED = 'false'
        AUTH_EXPOSE_DEV_CODE = 'false'
        MODEL_API_KEY = ''
        PISTON_BASE_URL = ''
        KNOWLEDGE_DIR = $knowledgeDir
        RESOURCE_DIR = $resourceDir
    }
    foreach ($key in $environmentKeys) {
        $oldEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, [string]$values[$key], 'Process')
    }

    $arguments = @('-jar', ('"' + (Resolve-Path -LiteralPath $JarPath).Path + '"'), '--spring.profiles.active=prod')
    $process = Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory (Split-Path -Parent (Resolve-Path -LiteralPath $JarPath).Path) -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $springReady = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($process.HasExited) { break }
        try {
            $health = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:${serverPort}/actuator/health" -TimeoutSec 3
            if ($health.StatusCode -eq 200) { $springReady = $true; break }
        } catch {}
        Start-Sleep -Seconds 1
    }
    if (-not $springReady) {
        $evidence = @((Get-Content -LiteralPath $stderrPath -ErrorAction SilentlyContinue) + (Get-Content -LiteralPath $stdoutPath -ErrorAction SilentlyContinue)) |
            Select-String -Pattern 'Flyway|Application run failed|Caused by|Communications|Access denied|SSL|Started' |
            Select-Object -Last 20 | ForEach-Object { $_.Line }
        throw "Spring health did not become ready; processExited=$($process.HasExited); evidence=$($evidence -join ' | ')"
    }

    $migrationCount = Invoke-Docker @('exec', '-e', "MYSQL_PWD=$rootPassword", $container, 'mysql', '-uroot', $database, '-NBe', 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=1')
    $snapshotTable = Invoke-Docker @('exec', '-e', "MYSQL_PWD=$rootPassword", $container, 'mysql', '-uroot', $database, '-NBe', 'SELECT COUNT(*) FROM dsvp_request_snapshots')
    $foreignKeys = Invoke-Docker @('exec', '-e', "MYSQL_PWD=$rootPassword", $container, 'mysql', '-uroot', $database, '-NBe', "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '$database' AND TABLE_NAME = 'dsvp_request_snapshots' AND CONSTRAINT_TYPE = 'FOREIGN KEY'")
    if ([int]$migrationCount -lt 11 -or [int]$snapshotTable -ne 0 -or [int]$foreignKeys -lt 1) {
        throw "MySQL schema verification failed: migrations=$migrationCount snapshotTableRows=$snapshotTable foreignKeys=$foreignKeys"
    }
    [pscustomobject]@{
        mysqlReady = $true
        springHealth = $true
        flywaySuccessfulMigrations = [int]$migrationCount
        dsvpSnapshotTablePresent = ([int]$snapshotTable -eq 0)
        dsvpSnapshotForeignKeys = [int]$foreignKeys
    } | ConvertTo-Json -Compress
} finally {
    if ($process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit(5000) | Out-Null }
    foreach ($key in $environmentKeys) { [Environment]::SetEnvironmentVariable($key, $oldEnvironment[$key], 'Process') }
    & docker rm -f $container 2>$null | Out-Null
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
}
