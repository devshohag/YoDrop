param([string]$TempDirectory)
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $TempDirectory) { $TempDirectory = Join-Path ([IO.Path]::GetPathRoot($projectRoot)) "Temp" }
New-Item -ItemType Directory -Path $TempDirectory -Force | Out-Null
$env:TEMP = (Resolve-Path $TempDirectory).Path
$env:TMP = $env:TEMP
Push-Location $projectRoot
try {
    & "$PSScriptRoot/prepare-p6-model.ps1"
    & "$PSScriptRoot/validate-step20.ps1" -TempDirectory $TempDirectory
    $result = [System.Collections.Generic.List[string]]::new()
    $result.Add("# Android redesign R1 owner validation")
    $result.Add("")
    $result.Add("Time: " + [DateTime]::UtcNow.ToString("o"))
    $head = git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw "Cannot identify checkout" }
    $result.Add("HEAD: $head; working-tree changes included in the build")
    foreach ($name in @("R1SecurityTest", "R1PairingFlowTest")) {
        $path = Join-Path $projectRoot "core/build/test-results/jvmTest/TEST-com.yodrop.core.$name.xml"
        if (-not (Test-Path -LiteralPath $path)) { throw "Missing R1 test suite: $name" }
        [xml]$report = Get-Content -LiteralPath $path -Raw
        $suite = $report.testsuite
        if ([int]$suite.tests -le 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "R1 suite not fully green: $name"
        }
        $result.Add("${name}: $($suite.tests) tests; failures=$($suite.failures); errors=$($suite.errors); skipped=$($suite.skipped)")
    }
    $log = Get-Content (Join-Path $projectRoot "build/reports/step20/gradle.log") -Raw
    if (-not $log.Contains("R1 TCP: signed session identities")) { throw "Missing R1 authenticated real-TCP evidence" }
    $result.Add("R1 authenticated identity/consent real TCP 1 MiB: PASS (loopback only)")
    $result.Add("")
    $result.Add("BUILD/JVM: PASS. Physical Keystore/BLE/receive-window acceptance: PENDING.")
    $result.Add("")
    $result.Add("Underlying full build, all JVM totals, merged manifest and APK hashes:")
    $result.Add((Get-Content (Join-Path $projectRoot "build/reports/step20/OWNER-VALIDATION.md") -Raw))
    $directory = Join-Path $projectRoot "build/reports/r1"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $result | Set-Content (Join-Path $directory "OWNER-VALIDATION.md") -Encoding UTF8
    Write-Host "R1 validation: $directory/OWNER-VALIDATION.md" -ForegroundColor Green
} finally { Pop-Location }
