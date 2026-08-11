[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [string]$ZipPath,

  [Parameter(Position = 1)]
  [string]$Destination
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent $PSScriptRoot
$resolvedZip = (Resolve-Path -LiteralPath $ZipPath).Path
if (-not $Destination) {
  $Destination = Join-Path $repoRoot "private\knowledge"
} elseif (-not [System.IO.Path]::IsPathRooted($Destination)) {
  $Destination = Join-Path $repoRoot $Destination
}

$destinationRoot = [System.IO.Path]::GetFullPath($Destination)
$destinationPrefix = $destinationRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null

$archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedZip)
$lessonCount = 0
$lessonIndexCount = 0
$answerCount = 0
$readmeCount = 0

try {
  foreach ($entry in $archive.Entries) {
    if (-not $entry.Name) { continue }

    $archivePath = $entry.FullName.Replace("\", "/")
    if ($archivePath -match '(^/)|(^[A-Za-z]:)|(^|/)\.\.(/|$)') {
      throw "Unsafe archive path: $archivePath"
    }

    $isLesson = $archivePath -match '^lessons/[^/]+\.md$'
    $isAnswer = $archivePath -eq 'raw/answer_by_chapter.json'
    $isReadme = $archivePath -eq 'README.md'
    if (-not ($isLesson -or $isAnswer -or $isReadme)) { continue }

    $relativePath = $archivePath.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    $targetPath = [System.IO.Path]::GetFullPath((Join-Path $destinationRoot $relativePath))
    if (-not $targetPath.StartsWith($destinationPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
      throw "Archive path escapes destination: $archivePath"
    }

    $targetDir = Split-Path -Parent $targetPath
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    $inputStream = $entry.Open()
    $outputStream = [System.IO.File]::Open($targetPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
    try {
      $inputStream.CopyTo($outputStream)
    } finally {
      $outputStream.Dispose()
      $inputStream.Dispose()
    }

    if ($isLesson -and $entry.Name -eq '00-lesson-index.md') {
      $lessonIndexCount += 1
    } elseif ($isLesson) {
      $lessonCount += 1
    }
    if ($isAnswer) { $answerCount += 1 }
    if ($isReadme) { $readmeCount += 1 }
  }
} finally {
  $archive.Dispose()
}

if ($lessonCount -eq 0) {
  throw "No lesson Markdown files were found in the archive."
}

Write-Output "Knowledge import complete"
Write-Output "Destination: $destinationRoot"
Write-Output "Lessons: $lessonCount"
Write-Output "Lesson indexes: $lessonIndexCount"
Write-Output "Answer files: $answerCount"
Write-Output "README files: $readmeCount"
Write-Output "Restart the Node.js service to rebuild the in-memory index."
