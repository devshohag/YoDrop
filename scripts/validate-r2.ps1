param([string]$TempDirectory)
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    & "$PSScriptRoot/validate-r1.ps1" -TempDirectory $TempDirectory
    $path = Join-Path $projectRoot "core/build/test-results/jvmTest/TEST-com.yodrop.core.R2CatchFlowTest.xml"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing R2CatchFlowTest suite" }
    [xml]$report = Get-Content -LiteralPath $path -Raw
    $suite = $report.testsuite
    if ([int]$suite.tests -lt 18 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
        throw "R2 Catch tests not fully green"
    }
    $log = Get-Content (Join-Path $projectRoot "build/reports/step20/gradle.log") -Raw
    if (-not $log.Contains("R2 TCP: authenticated selected-window Catch")) { throw "Missing R2 Catch real-TCP evidence" }
    $result = [System.Collections.Generic.List[string]]::new()
    $result.Add("# Android redesign R2 owner validation")
    $result.Add("")
    $result.Add("Time: " + [DateTime]::UtcNow.ToString("o"))
    $head = git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw "Cannot identify checkout" }
    $result.Add("HEAD: $head; working-tree changes included in the build")
    $result.Add("R2CatchFlowTest: $($suite.tests) tests; failures=$($suite.failures); errors=$($suite.errors); skipped=$($suite.skipped)")
    $result.Add("Authenticated selected-window Catch -> encrypted consent -> real TCP 1 MiB: PASS (loopback only)")
    $result.Add("")
    $result.Add("BUILD/JVM: PASS. Physical UI/camera/Keystore/BLE/two-phone acceptance: PENDING (R3).")
    $result.Add("See docs/R2-VALIDATION.md for the first-pair and returning trusted Catch flows.")
    $result.Add("")
    $result.Add((Get-Content (Join-Path $projectRoot "build/reports/r1/OWNER-VALIDATION.md") -Raw))
    $directory = Join-Path $projectRoot "build/reports/r2"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $result | Set-Content (Join-Path $directory "OWNER-VALIDATION.md") -Encoding UTF8
    Write-Host "R2 validation: $directory/OWNER-VALIDATION.md" -ForegroundColor Green
} finally { Pop-Location }
