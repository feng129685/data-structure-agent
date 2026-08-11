[CmdletBinding()]
param(
    [string]$NodeImageSource = 'structify-node:rehearsal',
    [string]$SpringImageSource = 'structify-spring:rehearsal',
    [string]$SpringJarPath = '',
    [switch]$BuildNodeImage,
    [switch]$KeepArtifacts,
    [int]$ServiceTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$defaultSpringJarPath = Join-Path $repoRoot 'backend\spring\target\ds-agent-server-0.0.1-SNAPSHOT.jar'
if ([string]::IsNullOrWhiteSpace($SpringJarPath)) {
    $SpringJarPath = $defaultSpringJarPath
}
$composeFile = Join-Path $repoRoot 'deployment\docker-compose.production.yml'
$backupScript = Join-Path $repoRoot 'deployment\scripts\backup.sh'
$restoreScript = Join-Path $repoRoot 'deployment\scripts\restore.sh'
$runId = [guid]::NewGuid().ToString('N')
$runRoot = Join-Path ([IO.Path]::GetTempPath()) "structify-backup-restore-$runId"
$logRoot = Join-Path $runRoot 'logs'
$permissionShimRoot = Join-Path $runRoot 'git-bash-permission-shims'
$springStage = Join-Path $runRoot 'spring-image'
$backupRoot = Join-Path $runRoot 'backups'
$privateRoot = Join-Path $runRoot 'private'
$knowledgeRoot = Join-Path $privateRoot 'knowledge'
$resourceRoot = Join-Path $privateRoot 'course-content'
$presentationRoot = Join-Path $privateRoot 'presentation-materials'
$pdfSourceRoot = Join-Path $privateRoot 'pdfs'
$envFile = Join-Path $runRoot 'rehearsal.env'

$projectName = "structify-backup-restore-$($runId.Substring(0, 12))"
$nodeImage = "structify-node:backup-restore-$($runId.Substring(0, 12))"
$springImage = "structify-spring:backup-restore-$($runId.Substring(0, 12))"
$mysqlDatabase = "structify_rehearsal_$($runId.Substring(0, 8))"
$mysqlUser = "rehearsal_$($runId.Substring(0, 8))"
$mysqlRootPassword = $null
$mysqlPassword = $null
$jwtSecret = $null
$nodeCompatSecret = $null
$composePrefix = $null
$gitBash = $null
$nodeImageTagged = $false
$springImageTagged = $false
$springImageBuilt = $false
$composeStarted = $false
$runSucceeded = $false
$restoreFailure = $null

function Write-Info([string]$Message) {
    Write-Host "[INFO] $Message"
}

function Write-Check([string]$Message) {
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Write-WarningSafe([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function New-RandomSecret([int]$Length) {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $bytes = New-Object byte[] $Length
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }

    $builder = New-Object Text.StringBuilder
    foreach ($byte in $bytes) {
        [void]$builder.Append($alphabet[$byte % $alphabet.Length])
    }
    return $builder.ToString()
}

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function ConvertTo-ComposePath([string]$Path) {
    return ((Resolve-Path -LiteralPath $Path).Path -replace '\\', '/')
}

function ConvertTo-GitBashPath([string]$Path) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    if ($fullPath -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Git Bash rehearsal path must use a local drive: $fullPath"
    }
    return "/$($Matches[1].ToLowerInvariant())/$($Matches[2] -replace '\\', '/')"
}

function Get-GitBash {
    $candidates = @(
        'C:\Program Files\Git\bin\bash.exe',
        'C:\Program Files\Git\usr\bin\bash.exe'
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    $command = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }
    throw 'Git Bash is required to execute the repository backup.sh and restore.sh scripts.'
}

function Invoke-Docker([string[]]$Arguments, [string]$Label = 'Docker command') {
    $stderrPath = Join-Path $logRoot ("docker-stderr-{0}.log" -f ([guid]::NewGuid().ToString('N')))
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& docker @Arguments 2> $stderrPath)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $stderr = @()
    if (Test-Path -LiteralPath $stderrPath) {
        $stderr = @(Get-Content -LiteralPath $stderrPath)
    }
    $combinedOutput = @($output) + @($stderr)
    if ($exitCode -ne 0) {
        $safeLog = Join-Path $logRoot ("docker-{0}.log" -f ([guid]::NewGuid().ToString('N')))
        ($combinedOutput | ForEach-Object { $_.ToString() }) | Set-Content -LiteralPath $safeLog -Encoding UTF8
        throw "$Label failed (exit code $exitCode)."
    }
    return ($output | ForEach-Object { $_.ToString() })
}

function Test-Docker([string[]]$Arguments) {
    $null = & docker @Arguments 2>$null
    return ($LASTEXITCODE -eq 0)
}

function Invoke-Compose([string[]]$Arguments, [string]$Label = 'Compose command') {
    return Invoke-Docker ($composePrefix + $Arguments) $Label
}

function Invoke-BashScript([string]$ScriptPath, [string[]]$Arguments, [string]$Label) {
    $stderrPath = Join-Path $logRoot ("bash-stderr-{0}.log" -f ([guid]::NewGuid().ToString('N')))
    $previousErrorAction = $ErrorActionPreference
    $previousShim = [Environment]::GetEnvironmentVariable('STRUCTIFY_REHEARSAL_SHIM_DIR', 'Process')
    $previousArgConversion = [Environment]::GetEnvironmentVariable('MSYS2_ARG_CONV_EXCL', 'Process')
    $ErrorActionPreference = 'Continue'
    try {
        $env:STRUCTIFY_REHEARSAL_SHIM_DIR = ConvertTo-GitBashPath $permissionShimRoot
        $env:MSYS2_ARG_CONV_EXCL = '/app;/bin;/tmp;/restore'
        $output = @(& $gitBash -lc 'export PATH="$STRUCTIFY_REHEARSAL_SHIM_DIR:$PATH"; exec "$@"' bash $ScriptPath @Arguments 2> $stderrPath)
        $exitCode = $LASTEXITCODE
    } finally {
        if ($null -eq $previousShim) {
            [Environment]::SetEnvironmentVariable('STRUCTIFY_REHEARSAL_SHIM_DIR', $null, 'Process')
        } else {
            $env:STRUCTIFY_REHEARSAL_SHIM_DIR = $previousShim
        }
        if ($null -eq $previousArgConversion) {
            [Environment]::SetEnvironmentVariable('MSYS2_ARG_CONV_EXCL', $null, 'Process')
        } else {
            $env:MSYS2_ARG_CONV_EXCL = $previousArgConversion
        }
        $ErrorActionPreference = $previousErrorAction
    }
    $stderr = @()
    if (Test-Path -LiteralPath $stderrPath) {
        $stderr = @(Get-Content -LiteralPath $stderrPath)
    }
    $safeLog = Join-Path $logRoot ("bash-{0}.log" -f ([guid]::NewGuid().ToString('N')))
    (@($output) + @($stderr) | ForEach-Object { $_.ToString() }) | Set-Content -LiteralPath $safeLog -Encoding UTF8
    if ($exitCode -ne 0) {
        throw "$Label failed (exit code $exitCode)."
    }
    return ($output | ForEach-Object { $_.ToString() })
}

function Get-ServiceContainer([string]$Service) {
    $ids = @((Invoke-Compose @('ps', '-q', $Service) "Locate $Service container") |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -ne '' })
    if ($ids.Count -lt 1) {
        throw "Compose did not create a container for service $Service."
    }
    $name = (Invoke-Docker @('inspect', '--format', '{{.Name}}', $ids[0]) "Inspect $Service container").Trim()
    return $name.TrimStart('/')
}

function Wait-MySql {
    $deadline = (Get-Date).AddSeconds($ServiceTimeoutSeconds)
    $args = $composePrefix + @('exec', '-T', 'mysql', 'mysqladmin', 'ping', '-h', '127.0.0.1', '--silent')
    do {
        if (Test-Docker $args) {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "MySQL did not become ready within $ServiceTimeoutSeconds seconds."
}

function Wait-Http([string]$Uri, [string]$Label) {
    $deadline = (Get-Date).AddSeconds($ServiceTimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                return
            }
        } catch {
            # The application may still be running migrations or binding its port.
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Label did not become healthy within $ServiceTimeoutSeconds seconds."
}

function Wait-Services {
    Wait-MySql
    Wait-Http "http://127.0.0.1:$nodePort/healthz" 'Node'
    Wait-Http "http://127.0.0.1:$springPort/actuator/health" 'Spring API'
}

function Invoke-NodeScript([string]$JavaScript, [string]$Label) {
    $result = Invoke-Compose @('exec', '-T', 'node', 'node', '-e', $JavaScript) $Label
    return (($result -join "`n").Trim())
}

function Set-NodeMarker([string]$Marker) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Marker))
    $script = @"
const Database = require('better-sqlite3');
const marker = Buffer.from('$encoded', 'base64').toString('utf8');
const db = new Database(process.env.DB_PATH);
db.exec('CREATE TABLE IF NOT EXISTS structify_backup_restore_marker (id INTEGER PRIMARY KEY, marker TEXT NOT NULL)');
db.prepare('INSERT INTO structify_backup_restore_marker (id, marker) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET marker = excluded.marker').run(marker);
db.close();
"@
    Invoke-NodeScript $script 'Write Node SQLite marker' | Out-Null
}

function Get-NodeMarker {
    $script = @'
const Database = require('better-sqlite3');
const db = new Database(process.env.DB_PATH, { readonly: true, fileMustExist: true });
const row = db.prepare('SELECT marker FROM structify_backup_restore_marker WHERE id = 1').get();
db.close();
if (!row) process.exit(2);
process.stdout.write(String(row.marker));
'@
    return Invoke-NodeScript $script 'Read Node SQLite marker'
}

function Set-PdfMarker([string]$Marker) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Marker))
    $script = "require('node:fs').writeFileSync('/app/pdfs/backup-restore-marker.txt', Buffer.from('$encoded', 'base64'))"
    Invoke-NodeScript $script 'Write Node PDF marker' | Out-Null
}

function Get-PdfMarker {
    $script = @'
const fs = require('node:fs');
process.stdout.write(fs.readFileSync('/app/pdfs/backup-restore-marker.txt', 'utf8'));
'@
    return Invoke-NodeScript $script 'Read Node PDF marker'
}

function Get-SeededPdf {
    $script = @'
const fs = require('node:fs');
process.stdout.write(fs.readFileSync('/app/pdfs/release-seed.pdf', 'utf8'));
'@
    return Invoke-NodeScript $script 'Read seeded Node PDF'
}

function Get-UserUploadPdf {
    $script = @'
const fs = require('node:fs');
process.stdout.write(fs.readFileSync('/app/pdfs/user-upload.pdf', 'utf8'));
'@
    return Invoke-NodeScript $script 'Read existing user-uploaded PDF'
}

function Invoke-MySqlSql([string]$Sql, [string]$Label) {
    $sqlPath = Join-Path $runRoot ("mysql-{0}.sql" -f ([guid]::NewGuid().ToString('N')))
    [IO.File]::WriteAllText($sqlPath, $Sql, [Text.UTF8Encoding]::new($false))
    $container = Get-ServiceContainer 'mysql'
    $containerFile = '/tmp/structify-backup-restore.sql'
    try {
        Invoke-Docker @('cp', $sqlPath, "${container}:$containerFile") "Copy SQL for $Label" | Out-Null
        $command = 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" --batch --skip-column-names --raw < /tmp/structify-backup-restore.sql 2>/dev/null'
        $result = Invoke-Compose @('exec', '-T', 'mysql', 'sh', '-c', $command) $Label
        return (($result -join "`n").Trim())
    } finally {
        try {
            Invoke-Docker @('exec', $container, 'rm', '-f', $containerFile) 'Remove temporary SQL' | Out-Null
        } catch {
            # Container cleanup is retried by the final Compose teardown.
        }
        Remove-Item -LiteralPath $sqlPath -Force -ErrorAction SilentlyContinue
    }
}

function Set-MySqlMarker([string]$Marker) {
    $escaped = $Marker.Replace("'", "''")
    $sql = @"
CREATE TABLE IF NOT EXISTS structify_backup_restore_marker (
  id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  marker VARCHAR(255) NOT NULL
);
DELETE FROM structify_backup_restore_marker WHERE id = 1;
INSERT INTO structify_backup_restore_marker (id, marker) VALUES (1, '$escaped');
"@
    Invoke-MySqlSql $sql 'Write MySQL marker' | Out-Null
}

function Get-MySqlMarker {
    $sql = 'SELECT marker FROM structify_backup_restore_marker WHERE id = 1;'
    return Invoke-MySqlSql $sql 'Read MySQL marker'
}

function Assert-Equal([string]$Actual, [string]$Expected, [string]$Label) {
    if ($Actual -ne $Expected) {
        throw "$Label did not match the expected marker."
    }
    Write-Check $Label
}

function Assert-Hashes([string]$BackupDirectory) {
    $hashFile = Join-Path $BackupDirectory 'SHA256SUMS'
    if (-not (Test-Path -LiteralPath $hashFile -PathType Leaf)) {
        throw 'Backup did not contain SHA256SUMS.'
    }
    $lines = @(Get-Content -LiteralPath $hashFile)
    if ($lines.Count -eq 0) {
        throw 'SHA256SUMS was empty.'
    }
    foreach ($line in $lines) {
        if ($line -notmatch '^([0-9a-fA-F]{64})[ \t]+[*]?(.+)$') {
            throw 'SHA256SUMS contained an invalid line.'
        }
        $expectedHash = $Matches[1].ToLowerInvariant()
        $relativeName = $Matches[2].Trim()
        $target = Join-Path $BackupDirectory $relativeName
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            throw "Backup hash referenced a missing artifact: $relativeName"
        }
        $actualHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $expectedHash) {
            throw "Backup hash mismatch for $relativeName."
        }
    }
    Write-Check "Backup SHA-256 hashes verified ($($lines.Count) artifacts)"
}

function Assert-BackupArtifacts([string]$BackupDirectory) {
    $required = @('mysql.sql', 'node.sqlite', 'node-pdfs.tar.gz', 'private.tar.gz', 'manifest.txt', 'SHA256SUMS')
    foreach ($name in $required) {
        $path = Join-Path $BackupDirectory $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Backup artifact is missing: $name"
        }
        if ((Get-Item -LiteralPath $path).Length -le 0) {
            throw "Backup artifact is empty: $name"
        }
    }
    $manifest = Get-Content -Raw -LiteralPath (Join-Path $BackupDirectory 'manifest.txt')
    foreach ($secret in @($mysqlRootPassword, $mysqlPassword, $jwtSecret, $nodeCompatSecret)) {
        if ($manifest.Contains($secret)) {
            throw 'A generated secret appeared in backup metadata.'
        }
    }
    Write-Check 'Backup contains MySQL, SQLite, PDF, private-media, manifest, and hash artifacts'
}

function Remove-GeneratedPath([string]$Path, [string]$AllowedRoot) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path.TrimEnd('\')
    $resolvedRoot = (Resolve-Path -LiteralPath $AllowedRoot).Path.TrimEnd('\')
    if (-not $resolvedPath.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase) -or $resolvedPath -eq $resolvedRoot) {
        throw "Refusing to remove a path outside the generated rehearsal root: $resolvedPath"
    }
    Remove-Item -LiteralPath $resolvedPath -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) { throw "Compose file is missing: $composeFile" }
if (-not (Test-Path -LiteralPath $backupScript -PathType Leaf)) { throw "Backup script is missing: $backupScript" }
if (-not (Test-Path -LiteralPath $restoreScript -PathType Leaf)) { throw "Restore script is missing: $restoreScript" }
if (-not (Test-Path -LiteralPath $SpringJarPath -PathType Leaf)) { throw "Spring executable JAR is missing: $SpringJarPath" }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required.' }

New-Item -ItemType Directory -Force -Path $logRoot, $permissionShimRoot, $springStage, $backupRoot, $knowledgeRoot, $resourceRoot, $presentationRoot, $pdfSourceRoot | Out-Null
$mkdirShim = @'
#!/usr/bin/env bash
set -eu
args=()
while (($#)); do
  case "$1" in
    -m|--mode) shift 2 ;;
    --mode=*) shift ;;
    *) args+=("$1"); shift ;;
  esac
done
exec /usr/bin/mkdir "${args[@]}"
'@
$chmodShim = @'
#!/usr/bin/env sh
# Git Bash on NTFS cannot enforce POSIX modes. The production Linux scripts
# keep their real chmod calls; this isolated rehearsal verifies data recovery.
exit 0
'@
[IO.File]::WriteAllText((Join-Path $permissionShimRoot 'mkdir'), ($mkdirShim -replace "`r`n", "`n"), [Text.Encoding]::ASCII)
[IO.File]::WriteAllText((Join-Path $permissionShimRoot 'chmod'), ($chmodShim -replace "`r`n", "`n"), [Text.Encoding]::ASCII)
$mysqlRootPassword = New-RandomSecret 48
$mysqlPassword = New-RandomSecret 48
$jwtSecret = New-RandomSecret 96
$nodeCompatSecret = New-RandomSecret 96
$nodePort = Get-FreeTcpPort
$springPort = Get-FreeTcpPort
$mysqlMarker = "mysql-marker-$runId"
$sqliteMarker = "sqlite-marker-$runId"
$pdfMarker = "pdf-marker-$runId"
$mutatedMarker = "mutated-$runId"
$sourcePdfInitial = "source-pdf-v1-$runId"
$sourcePdfUpdated = "source-pdf-v2-$runId"
$sourcePdfPath = Join-Path $pdfSourceRoot 'release-seed.pdf'
$sourcePdfCollision = "source-pdf-collision-$runId"
$sourcePdfCollisionPath = Join-Path $pdfSourceRoot 'user-upload.pdf'
$legacyUpload = "user-upload-v1-$runId"
[IO.File]::WriteAllText($sourcePdfPath, $sourcePdfInitial, [Text.Encoding]::ASCII)
[IO.File]::WriteAllText($sourcePdfCollisionPath, $sourcePdfCollision, [Text.Encoding]::ASCII)
$gitBash = Get-GitBash

$composePrefix = @('compose', '--env-file', $envFile, '-f', $composeFile, '-p', $projectName)

try {
    $envLines = @(
        "COMPOSE_PROJECT_NAME=$projectName",
        'CADDY_MODE=host',
        "NODE_HOST_PORT=$nodePort",
        "SPRING_HOST_PORT=$springPort",
        'NODE_BASE_IMAGE=node:22-bookworm-slim',
        'JAVA_BUILD_IMAGE=eclipse-temurin:21-jdk',
        'JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre',
        "NODE_IMAGE=$nodeImage",
        "SPRING_IMAGE=$springImage",
        "MYSQL_DATABASE=$mysqlDatabase",
        "MYSQL_USER=$mysqlUser",
        "MYSQL_PASSWORD=$mysqlPassword",
        "MYSQL_ROOT_PASSWORD=$mysqlRootPassword",
        'DB_URL=jdbc:mysql://mysql:3306/structify_rehearsal',
        "JWT_SECRET=$jwtSecret",
        'NODE_COMPAT_ENABLED=true',
        "NODE_COMPAT_JWT_SECRET=$nodeCompatSecret",
        'AUTH_TOKEN_ISSUER=structify.cn',
        'AUTH_TOKEN_TTL=PT2H',
        'AUTH_COOKIE_NAME=ds_session',
        'AUTH_COOKIE_SECURE=true',
        'AUTH_EXPOSE_DEV_CODE=false',
        'AUTH_MAIL_ENABLED=false',
        'BOOTSTRAP_ADMIN_EMAIL=',
        'TEACHER_EMAILS=',
        'ALLOW_FIRST_USER_TEACHER=false',
        'MODEL_PROVIDER=',
        'MODEL_API_KEY=',
        'MODEL_BASE_URL=',
        'MODEL_NAME=',
        "KNOWLEDGE_DIR_HOST=$(ConvertTo-ComposePath $knowledgeRoot)",
        "RESOURCE_DIR_HOST=$(ConvertTo-ComposePath $resourceRoot)",
        "PRESENTATION_DIR_HOST=$(ConvertTo-ComposePath $presentationRoot)",
        "PDF_SOURCE_DIR_HOST=$(ConvertTo-ComposePath $pdfSourceRoot)",
        'KNOWLEDGE_DEBUG_API=false',
        'KNOWLEDGE_SEARCH_LIMIT=4',
        'KNOWLEDGE_CONTEXT_MAX_CHARS=3600',
        'KNOWLEDGE_MIN_SCORE=8',
        'PRESENTATION_ASSET_URL_TTL_SECONDS=900',
        'JUDGE0_BASE_URL=',
        'PISTON_BASE_URL=',
        'SMTP_HOST=',
        'SMTP_CONNECT_HOST=',
        'SMTP_TLS_SERVERNAME=',
        'SMTP_PORT=465',
        'SMTP_USER=',
        'SMTP_PASS=',
        'SMTP_FROM=',
        'EXECUTE_RATE_WINDOW_MS=60000',
        'EXECUTE_RATE_MAX=8',
        'EXECUTE_MAX_CONCURRENT=4',
        'EXECUTE_PER_IP_CONCURRENCY=2',
        'EXECUTE_TIMEOUT_MS=15000',
        'EXECUTE_PROVIDER_TIMEOUT_MS=8000',
        'EXECUTE_OUTPUT_MAX_LENGTH=6000',
        'PDF_UPLOAD_MAX_BYTES=26214400',
        'PDF_FILE_MAX_BYTES=20971520',
        'PDF_UPLOAD_MAX_FILES=5',
        'UPLOAD_REQUEST_MAX_BYTES=22020096'
    )
    [IO.File]::WriteAllLines($envFile, $envLines, [Text.Encoding]::ASCII)
    Write-Info "Created isolated Compose project $projectName on loopback ports $nodePort/$springPort."

    if (-not [string]::IsNullOrWhiteSpace($NodeImageSource) -and (Test-Docker @('image', 'inspect', $NodeImageSource))) {
        Invoke-Docker @('tag', $NodeImageSource, $nodeImage) 'Tag temporary Node image' | Out-Null
        $nodeImageTagged = $true
        Write-Check "Temporary Node image prepared from $NodeImageSource"
    } elseif ($BuildNodeImage) {
        Invoke-Compose @('build', '--pull=false', 'node') 'Build temporary Node image' | Out-Null
        $nodeImageTagged = $true
        Write-Check 'Temporary Node image built from the production Dockerfile'
    } else {
        throw "Node source image is unavailable: $NodeImageSource. Pass -BuildNodeImage or -NodeImageSource with a local image."
    }

    if (-not [string]::IsNullOrWhiteSpace($SpringImageSource) -and (Test-Docker @('image', 'inspect', $SpringImageSource))) {
        Invoke-Docker @('tag', $SpringImageSource, $springImage) 'Tag temporary Spring image' | Out-Null
        $springImageTagged = $true
        Write-Check "Temporary Spring image prepared from $SpringImageSource"
    } else {
        $springDockerfile = @'
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY app.jar /app/app.jar
USER appuser
EXPOSE 8792
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
'@
        [IO.File]::WriteAllText((Join-Path $springStage 'Dockerfile'), $springDockerfile, [Text.Encoding]::ASCII)
        Copy-Item -LiteralPath (Resolve-Path -LiteralPath $SpringJarPath).Path -Destination (Join-Path $springStage 'app.jar') -Force
        Invoke-Docker @('build', '--pull=false', '--tag', $springImage, $springStage) 'Build temporary Spring image' | Out-Null
        $springImageBuilt = $true
        Write-Check 'Temporary Spring image built from the local executable JAR'
    }

    Invoke-Compose @(
        'run', '--rm', '--no-deps', '-T', '--entrypoint', '/bin/sh', 'node',
        '-c', "set -eu; touch /app/pdfs/.seeded; printf '%s' '$legacyUpload' > /app/pdfs/user-upload.pdf"
    ) 'Prepare legacy Node PDF volume' | Out-Null

    Invoke-Compose @('up', '--detach', '--no-build', 'mysql', 'node', 'spring-api') 'Start isolated Compose services' | Out-Null
    $composeStarted = $true
    $mysqlContainer = Get-ServiceContainer 'mysql'
    $nodeContainer = Get-ServiceContainer 'node'
    $springContainer = Get-ServiceContainer 'spring-api'
    $expectedContainers = @(
        "$projectName-mysql-1",
        "$projectName-node-1",
        "$projectName-spring-api-1"
    )
    $actualContainers = @($mysqlContainer, $nodeContainer, $springContainer)
    foreach ($expected in $expectedContainers) {
        if ($actualContainers -notcontains $expected) {
            throw "Compose container naming did not match the isolated project: $expected"
        }
    }
    Write-Check 'MySQL, Node, and Spring containers have explicit project-scoped names'
    Wait-Services
    Write-Check 'Initial Compose services became healthy'
    Assert-Equal (Get-SeededPdf) $sourcePdfInitial 'Course PDF source seeded despite legacy volume marker'
    Assert-Equal (Get-UserUploadPdf) $legacyUpload 'Existing user-uploaded PDF was not overwritten by course seed'

    [IO.File]::WriteAllText($sourcePdfPath, $sourcePdfUpdated, [Text.Encoding]::ASCII)
    Invoke-Compose @('restart', 'node') 'Restart Node to verify PDF seed is first-boot only' | Out-Null
    Wait-Services
    Assert-Equal (Get-SeededPdf) $sourcePdfInitial 'Existing Node PDF volume was not overwritten after restart'
    Assert-Equal (Get-UserUploadPdf) $legacyUpload 'Existing user-uploaded PDF remained after restart'

    Set-MySqlMarker $mysqlMarker
    Set-NodeMarker $sqliteMarker
    Set-PdfMarker $pdfMarker
    Assert-Equal (Get-MySqlMarker) $mysqlMarker 'Initial MySQL marker'
    Assert-Equal (Get-NodeMarker) $sqliteMarker 'Initial Node SQLite marker'
    Assert-Equal (Get-PdfMarker) $pdfMarker 'Initial Node PDF volume marker'

    Invoke-BashScript $backupScript @(
        '--env-file', (ConvertTo-GitBashPath $envFile),
        '--backup-root', (ConvertTo-GitBashPath $backupRoot),
        '--private-root', (ConvertTo-GitBashPath $privateRoot),
        '--execute', '--confirm', 'BACKUP-structify.cn'
    ) 'Backup rehearsal' | Out-Null
    $backupDirectories = @(Get-ChildItem -LiteralPath $backupRoot -Directory)
    if ($backupDirectories.Count -ne 1) {
        throw 'Backup rehearsal did not create exactly one timestamped directory.'
    }
    $backupDirectory = $backupDirectories[0].FullName
    Assert-BackupArtifacts $backupDirectory
    Assert-Hashes $backupDirectory

    Set-MySqlMarker $mutatedMarker
    Set-NodeMarker $mutatedMarker
    Set-PdfMarker $mutatedMarker
    Assert-Equal (Get-MySqlMarker) $mutatedMarker 'Mutation reached MySQL before restore'
    Assert-Equal (Get-NodeMarker) $mutatedMarker 'Mutation reached Node SQLite before restore'
    Assert-Equal (Get-PdfMarker) $mutatedMarker 'Mutation reached Node PDF volume before restore'

    try {
        Invoke-BashScript $restoreScript @(
            '--env-file', (ConvertTo-GitBashPath $envFile),
            '--backup-dir', (ConvertTo-GitBashPath $backupDirectory),
            '--execute', '--confirm', 'RESTORE-structify.cn'
        ) 'Restore rehearsal' | Out-Null
    } catch {
        $restoreFailure = $_.Exception.Message
        Write-WarningSafe 'restore.sh returned a non-zero exit code; post-restore data and health checks will classify the failure.'
    }

    Wait-Services
    Assert-Equal (Get-MySqlMarker) $mysqlMarker 'Restored MySQL marker'
    Assert-Equal (Get-NodeMarker) $sqliteMarker 'Restored Node SQLite marker'
    Assert-Equal (Get-PdfMarker) $pdfMarker 'Restored Node PDF volume marker'
    if ($null -ne $restoreFailure) {
        throw 'restore.sh completed data restoration but failed its immediate health-check command.'
    }
    Write-Check 'Restore command completed and restored all markers'
    $runSucceeded = $true
    Write-Host 'Compose backup/restore rehearsal passed.' -ForegroundColor Green
} catch {
    Write-Host "Compose backup/restore rehearsal failed: $($_.Exception.Message)" -ForegroundColor Red
    throw
} finally {
    if ($composeStarted) {
        try {
            Invoke-Compose @('down', '--volumes', '--remove-orphans') 'Tear down rehearsal Compose project' | Out-Null
        } catch {
            Write-WarningSafe 'The rehearsal Compose teardown returned an error; inspect only the generated project resources.'
        }
    }
    if ($nodeImageTagged) {
        try { Invoke-Docker @('image', 'rm', '--force', $nodeImage) 'Remove temporary Node image' | Out-Null } catch {}
    }
    if ($springImageTagged -or $springImageBuilt) {
        try { Invoke-Docker @('image', 'rm', '--force', $springImage) 'Remove temporary Spring image' | Out-Null } catch {}
    }
    if (-not $KeepArtifacts) {
        try { Remove-GeneratedPath $runRoot ([IO.Path]::GetTempPath()) } catch {
            Write-WarningSafe 'Temporary rehearsal artifacts could not be removed automatically.'
        }
    } else {
        Write-Info "Temporary rehearsal artifacts retained at $runRoot"
    }
}

if (-not $runSucceeded) {
    exit 1
}
