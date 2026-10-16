$ErrorActionPreference = "Stop"

$projectRoot = Split-Path $PSScriptRoot -Parent
$commonRoot = Join-Path $projectRoot "core/src/commonMain"

$violations = @(
    Get-ChildItem $commonRoot -Recurse -Filter "*.kt" |
        Select-String -Pattern '\bjava\.|\bjavax\.|\bsynchronized\s*\(|\brunBlocking\b|\.format\s*\('
)

if ($violations.Count -gt 0) {
    $violations | ForEach-Object {
        Write-Host "$($_.Path):$($_.LineNumber): $($_.Line)"
    }
    throw "commonMain contains platform-only code."
}

Write-Host "commonMain portability check PASSED" -ForegroundColor Green
