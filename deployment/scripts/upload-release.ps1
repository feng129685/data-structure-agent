[CmdletBinding()]
param(
    [string]$Artifact,
    [string]$Release,
    [string]$HostName,
    [string]$User = "ubuntu",
    [ValidateRange(1, 65535)]
    [int]$Port = 22,
    [string]$ReleaseRoot = "/srv/structify/releases",
    [string]$RemoteUploadRoot = "/tmp/structify-upload",
    [string]$IdentityFile,
    [string]$CredentialFile = (Join-Path $env:LOCALAPPDATA "Structify\credentials\production-ssh.xml"),
    [switch]$SaveCredential,
    [switch]$Execute,
    [string]$Confirm,
    [switch]$Help,
    [switch]$AskPass
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Show-Usage {
    @'
Usage:
  upload-release.ps1 -Artifact FILE -Release RELEASE -HostName HOST [options]
                      [-Execute -Confirm UPLOAD-structify.cn]

  upload-release.ps1 -HostName HOST [-User ubuntu] -SaveCredential

The default mode validates the local ZIP and prints a non-mutating upload plan.
Execute mode copies the archive with SCP, verifies SHA-256 on the host, and
atomically extracts it below /srv/structify/releases. It never runs deployment,
reads a production environment file, or changes DNS.

Password authentication uses a Windows DPAPI-protected credential file outside
the repository. -SaveCredential prompts securely and never accepts a plaintext
password argument. A known SSH host key is required.
'@
}

function Assert-SafeToken {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Pattern
    )
    if ($Value -notmatch $Pattern) {
        throw "$Name contains unsupported characters"
    }
}

function Assert-SafeRemotePath {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )
    if ($Value -notmatch '^/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$') {
        throw "$Name must be an absolute Linux path containing only safe path characters"
    }
    if (($Value -split '/') -contains '..') {
        throw "$Name must not contain parent traversal"
    }
}

function Resolve-RequiredCommand {
    param([Parameter(Mandatory = $true)][string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "$Name is required"
    }
    return $command.Source
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "native command failed with exit code ${LASTEXITCODE}: $Command"
    }
}

function Protect-CredentialFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $identity,
        [System.Security.AccessControl.FileSystemRights]::FullControl,
        [System.Security.AccessControl.AccessControlType]::Allow
    )
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Save-LocalCredential {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw "DPAPI credential storage is supported only on Windows"
    }
    if ([string]::IsNullOrWhiteSpace($HostName)) {
        throw "-HostName is required with -SaveCredential"
    }
    Assert-SafeToken -Name "HostName" -Value $HostName -Pattern '^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$'
    Assert-SafeToken -Name "User" -Value $User -Pattern '^[a-z_][a-z0-9_-]*$'

    $parent = Split-Path -Parent $CredentialFile
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $securePassword = Read-Host "SSH password for $User@$HostName" -AsSecureString
    $credential = [System.Management.Automation.PSCredential]::new($User, $securePassword)
    $credential | Export-Clixml -LiteralPath $CredentialFile -Force
    Protect-CredentialFile -Path $CredentialFile
    Write-Host "Credential saved locally with Windows DPAPI: $CredentialFile"
}

function Write-AskPassSecret {
    if ([string]::IsNullOrWhiteSpace($CredentialFile) -or -not (Test-Path -LiteralPath $CredentialFile -PathType Leaf)) {
        exit 1
    }
    $credential = Import-Clixml -LiteralPath $CredentialFile
    if ($credential -isnot [System.Management.Automation.PSCredential]) {
        exit 1
    }
    $secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($credential.Password)
    try {
        [Console]::Out.WriteLine([Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer))
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
    }
}

function Assert-ArchiveContract {
    param([Parameter(Mandatory = $true)][string]$Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $names = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::Ordinal)
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName
            if ([string]::IsNullOrWhiteSpace($name)) {
                continue
            }
            if ($name.StartsWith('/') -or $name.StartsWith('\') -or $name.Contains('\') -or $name -match '^[A-Za-z]:') {
                throw "release archive contains an unsafe path"
            }
            $segments = $name.TrimEnd('/') -split '/'
            if ($segments -contains '..') {
                throw "release archive contains parent traversal"
            }
            if ($name -match '(^|/)(node_modules|target|\.git)(/|$)' -or
                $name -match '(^|/)\.env(/|$)' -or
                $name -match '\.(?:sqlite|sqlite3|db)$') {
                throw "release archive contains a forbidden runtime or private file"
            }
            $unixType = (($entry.ExternalAttributes -shr 16) -band 0xF000)
            if ($unixType -eq 0xA000) {
                throw "release archive must not contain symbolic links"
            }
            [void]$names.Add($name.TrimEnd('/'))
        }

        $required = @(
            "backend/node/server.js",
            "backend/spring/pom.xml",
            "deployment/docker-compose.production.yml",
            "deployment/scripts/release.sh"
        )
        foreach ($requiredPath in $required) {
            if (-not $names.Contains($requiredPath)) {
                throw "release archive is missing $requiredPath"
            }
        }
    }
    finally {
        $archive.Dispose()
    }
}

if ($Help) {
    Show-Usage
    exit 0
}

if ($AskPass) {
    Write-AskPassSecret
    exit 0
}

if ($SaveCredential) {
    Save-LocalCredential
    exit 0
}

foreach ($requiredArgument in @{
    Artifact = $Artifact
    Release = $Release
    HostName = $HostName
}.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace([string]$requiredArgument.Value)) {
        throw "-$($requiredArgument.Key) is required"
    }
}

Assert-SafeToken -Name "Release" -Value $Release -Pattern '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
Assert-SafeToken -Name "HostName" -Value $HostName -Pattern '^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$'
Assert-SafeToken -Name "User" -Value $User -Pattern '^[a-z_][a-z0-9_-]*$'
Assert-SafeRemotePath -Name "ReleaseRoot" -Value $ReleaseRoot
Assert-SafeRemotePath -Name "RemoteUploadRoot" -Value $RemoteUploadRoot

$resolvedArtifact = (Resolve-Path -LiteralPath $Artifact -ErrorAction Stop).Path
$artifactItem = Get-Item -LiteralPath $resolvedArtifact
if ($artifactItem.PSIsContainer -or $artifactItem.Extension -ne ".zip") {
    throw "-Artifact must be a ZIP file"
}
Assert-ArchiveContract -Path $resolvedArtifact

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedArtifact).Hash.ToLowerInvariant()
$hashPrefix = $hash.Substring(0, 12)
$remoteArchive = "$RemoteUploadRoot/structify-$Release-$hashPrefix.zip"
$remotePart = "$remoteArchive.part"
$remoteTarget = "$ReleaseRoot/$Release"
$remoteTemporary = "$ReleaseRoot/.incoming-$Release-$hashPrefix"
$remoteEndpoint = "$User@$HostName"

Write-Host "Validated release upload plan"
Write-Host "  artifact: $resolvedArtifact"
Write-Host "  SHA-256: $hash"
Write-Host "  target: ${remoteEndpoint}:$remoteTarget"

if (-not $Execute) {
    Write-Host "Dry-run only. Re-run with -Execute -Confirm UPLOAD-structify.cn."
    exit 0
}
if ($Confirm -ne "UPLOAD-structify.cn") {
    throw "upload requires -Confirm UPLOAD-structify.cn"
}

$ssh = Resolve-RequiredCommand -Name "ssh"
$scp = Resolve-RequiredCommand -Name "scp"
$sshOptions = @(
    "-o", "StrictHostKeyChecking=yes",
    "-o", "ConnectTimeout=10",
    "-o", "ServerAliveInterval=15",
    "-o", "ServerAliveCountMax=2"
)
$scpOptions = @($sshOptions)
if (-not [string]::IsNullOrWhiteSpace($IdentityFile)) {
    $resolvedIdentity = (Resolve-Path -LiteralPath $IdentityFile -ErrorAction Stop).Path
    $sshOptions += @("-i", $resolvedIdentity, "-o", "IdentitiesOnly=yes")
    $scpOptions += @("-i", $resolvedIdentity, "-o", "IdentitiesOnly=yes")
}

$askPassWrapper = $null
$previousAskPass = $env:SSH_ASKPASS
$previousAskPassRequire = $env:SSH_ASKPASS_REQUIRE
$previousDisplay = $env:DISPLAY
$hasCredential = Test-Path -LiteralPath $CredentialFile -PathType Leaf
try {
    if ($hasCredential) {
        if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
            throw "DPAPI credential use is supported only on Windows"
        }
        $scriptPath = $MyInvocation.MyCommand.Path
        $askPassWrapper = Join-Path $env:TEMP ("structify-askpass-{0}.cmd" -f [guid]::NewGuid().ToString("N"))
        $askPassInvocation = "`$ProgressPreference = 'SilentlyContinue'; & '" + $scriptPath.Replace("'", "''") + "' -AskPass -CredentialFile '" + $CredentialFile.Replace("'", "''") + "'"
        $encodedAskPassInvocation = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($askPassInvocation))
        $wrapper = '@echo off' + [Environment]::NewLine +
            'powershell.exe -NoProfile -NonInteractive -OutputFormat Text -ExecutionPolicy Bypass -EncodedCommand ' + $encodedAskPassInvocation + [Environment]::NewLine
        Set-Content -LiteralPath $askPassWrapper -Value $wrapper -Encoding Ascii
        $env:SSH_ASKPASS = $askPassWrapper
        $env:SSH_ASKPASS_REQUIRE = "force"
        $env:DISPLAY = "structify-local"
        $sshOptions += @(
            "-o", "BatchMode=no",
            "-o", "PubkeyAuthentication=no",
            "-o", "PreferredAuthentications=password",
            "-o", "PasswordAuthentication=yes",
            "-o", "NumberOfPasswordPrompts=1"
        )
        $scpOptions += @(
            "-o", "BatchMode=no",
            "-o", "PubkeyAuthentication=no",
            "-o", "PreferredAuthentications=password",
            "-o", "PasswordAuthentication=yes",
            "-o", "NumberOfPasswordPrompts=1"
        )
    }
    else {
        $sshOptions += @("-o", "BatchMode=yes")
        $scpOptions += @("-o", "BatchMode=yes")
    }

    $precheck = @"
set -eu
command -v sha256sum >/dev/null
command -v unzip >/dev/null
command -v base64 >/dev/null
sudo -n true
install -d -m 700 -- '$RemoteUploadRoot'
test ! -e '$remotePart'
"@
    Invoke-NativeCommand -Command $ssh -Arguments ($sshOptions + @("-p", "$Port", $remoteEndpoint, $precheck))

    $destination = "${remoteEndpoint}:$remotePart"
    Invoke-NativeCommand -Command $scp -Arguments ($scpOptions + @("-P", "$Port", $resolvedArtifact, $destination))

    $finalize = @"
set -eu
expected='$hash'
archive='$remotePart'
root=`$(realpath -m -- '$ReleaseRoot')
target=`$(realpath -m -- '$remoteTarget')
stage=`$(realpath -m -- '$remoteTemporary')
case "`$target" in "`$root"/*) ;; *) echo 'unsafe release target' >&2; exit 1 ;; esac
case "`$stage" in "`$root"/*) ;; *) echo 'unsafe staging target' >&2; exit 1 ;; esac
actual=`$(sha256sum "`$archive" | awk '{print `$1}')
if [ "`$actual" != "`$expected" ]; then
  rm -f -- "`$archive"
  echo 'release checksum mismatch' >&2
  exit 1
fi
test ! -e "`$target"
test ! -e "`$stage"
mkdir -m 700 -- "`$stage"
cleanup() { rm -rf -- "`$stage"; }
trap cleanup EXIT HUP INT TERM
unzip -q "`$archive" -d "`$stage"
test -f "`$stage/backend/node/server.js"
test -f "`$stage/backend/spring/pom.xml"
test -f "`$stage/deployment/docker-compose.production.yml"
test -f "`$stage/deployment/scripts/release.sh"
chmod 755 "`$stage/deployment/scripts/"*.sh "`$stage/backend/spring/mvnw"
mv -- "`$stage" "`$target"
trap - EXIT HUP INT TERM
rm -f -- "`$archive"
printf 'release_source_ready=%s\nsha256=%s\n' "`$target" "`$actual"
"@
    $encodedFinalize = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($finalize))
    $finalizeCommand = "printf '%s' '$encodedFinalize' | base64 -d | sudo -n bash"
    Invoke-NativeCommand -Command $ssh -Arguments ($sshOptions + @("-p", "$Port", $remoteEndpoint, $finalizeCommand))
}
finally {
    if ($null -ne $askPassWrapper -and (Test-Path -LiteralPath $askPassWrapper)) {
        Remove-Item -LiteralPath $askPassWrapper -Force
    }
    $env:SSH_ASKPASS = $previousAskPass
    $env:SSH_ASKPASS_REQUIRE = $previousAskPassRequire
    $env:DISPLAY = $previousDisplay
}

Write-Host "Release source uploaded and verified. Run deployment/scripts/release.sh on the host after environment and backup review."
