param([string]$TempDirectory)
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path (Join-Path $projectRoot "gradlew.bat"))) { throw "Run from a YoDrop checkout" }
if (-not $TempDirectory) { $TempDirectory = Join-Path ([IO.Path]::GetPathRoot($projectRoot)) "Temp" }
New-Item -ItemType Directory -Path $TempDirectory -Force | Out-Null
$env:TEMP = (Resolve-Path $TempDirectory).Path
$env:TMP = $env:TEMP
$staging = Join-Path $env:TEMP ("YoDrop-step20-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $staging | Out-Null
$reportDirectory = Join-Path $projectRoot "build/reports/step20"
$gradleLog = Join-Path $staging "gradle.log"
$summary = [System.Collections.Generic.List[string]]::new()
$summary.Add("# Step 20 owner validation")
$summary.Add("")
$summary.Add("Time: " + [DateTime]::UtcNow.ToString("o"))
$summary.Add("Device acceptance: PENDING; this script does not execute physical tests.")
$failed = $null
Push-Location $projectRoot
try {
    $head = git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw "Cannot identify checkout" }
    $summary.Add("HEAD: $head")
    git status --short | Set-Content (Join-Path $staging "git-status.txt") -Encoding UTF8
    git log --reverse --stat --format=fuller ef0b818..4fb3bd4 |
        Set-Content (Join-Path $staging "steps1-19-history.txt") -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw "Cannot capture Steps 1-19 history" }

    & "$PSScriptRoot/check-common-main.ps1"
    & "$PSScriptRoot/check-android-manifest.ps1"
    git diff --check
    if ($LASTEXITCODE -ne 0) { throw "Whitespace audit failed" }
    $summary.Add("Source portability/manifest/whitespace: PASS")

    & .\gradlew.bat --stop
    if ($LASTEXITCODE -ne 0) { throw "Gradle stop failed" }
    $summary.Add("Command: gradlew.bat clean :core:jvmTest :android:assembleDebug :core:assembleDebugAndroidTest --console=plain --no-daemon")
    # Keep this log outside build/: clean would otherwise remove/lock its own log.
    # PS 5.1 wraps redirected native stderr as ErrorRecord; use exit code for Gradle failure.
    $ErrorActionPreference = "Continue"
    & .\gradlew.bat clean :core:jvmTest :android:assembleDebug :core:assembleDebugAndroidTest --console=plain --no-daemon 2>&1 |
        Tee-Object -FilePath $gradleLog
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($gradleExit -ne 0) { throw "Gradle failed with exit code $gradleExit" }

    $merged = @(Get-ChildItem .\android\build\intermediates\merged_manifests -Recurse -Filter AndroidManifest.xml |
        Where-Object { $_.FullName -match '[\\/]debug[\\/]' })
    if ($merged.Count -ne 1) { throw "Expected one debug merged manifest; found $($merged.Count)" }
    & "$PSScriptRoot/check-android-manifest.ps1" -ManifestPath $merged[0].FullName
    $summary.Add("Merged debug manifest: PASS")

    $reports = @(Get-ChildItem .\core\build\test-results\jvmTest -Filter "TEST-*.xml")
    if ($reports.Count -eq 0) { throw "No JVM test reports; refusing an empty pass" }
    $suites = @{}
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($file in $reports) {
        [xml]$xml = Get-Content $file.FullName -Raw
        $suite = $xml.testsuite
        if ($null -eq $suite -or [int]$suite.tests -le 0) { throw "Empty/invalid test suite: $($file.Name)" }
        $suites[[string]$suite.name] = $true
        $tests += [int]$suite.tests; $failures += [int]$suite.failures
        $errors += [int]$suite.errors; $skipped += [int]$suite.skipped
    }
    if ($failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) { throw "Tests not fully green: failures=$failures errors=$errors skipped=$skipped" }
    foreach ($name in @("com.yodrop.core.Step10LargeTransferTest", "com.yodrop.core.Step8ResumeTest",
        "com.yodrop.core.T7PipeliningAndStreamsTest", "com.yodrop.core.crypto.Step3X25519Test", "com.yodrop.core.Step20AuditTest")) {
        if (-not $suites.ContainsKey($name)) { throw "Required acceptance suite missing: $name" }
    }
    $summary.Add("JVM: $($reports.Count) suites; $tests tests; failures=$failures; errors=$errors; skipped=$skipped")
    Copy-Item $reports.FullName -Destination $staging
    $logText = Get-Content $gradleLog -Raw
    foreach ($marker in @("Step10 TCP: 1 GiB", "Step8 TCP: 256 MiB", "Step9 TCP: 4 authenticated sockets", "BUILD SUCCESSFUL")) {
        if (-not $logText.Contains($marker)) { throw "Required build evidence missing: $marker" }
    }
    foreach ($apk in @("android/build/outputs/apk/debug/android-debug.apk", "core/build/outputs/apk/androidTest/debug/core-debug-androidTest.apk")) {
        if (-not (Test-Path $apk)) { throw "Missing APK: $apk" }
        $hash = (Get-FileHash $apk -Algorithm SHA256).Hash
        $summary.Add("APK: $apk; SHA256=$hash")
    }
    $summary.Add("")
    $summary.Add("BUILD AND JVM CHECKS: PASS. PHYSICAL ACCEPTANCE: PENDING.")
} catch {
    $failed = $_
    $summary.Add("")
    $summary.Add("BUILD/AUDIT: FAILED - " + $_.Exception.Message)
} finally {
    if (Test-Path $gradleLog) {
        $summary.Add("")
        $summary.Add('```text')
        Get-Content $gradleLog -Tail 35 | ForEach-Object { $summary.Add([string]$_) }
        $summary.Add('```')
    }
    $summary | Set-Content (Join-Path $staging "OWNER-VALIDATION.md") -Encoding UTF8
    New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
    Copy-Item (Join-Path $staging "*") -Destination $reportDirectory -Force
    Remove-Item $staging -Recurse -Force
    Pop-Location
    Write-Host "Validation report: $reportDirectory/OWNER-VALIDATION.md"
}
if ($null -ne $failed) { throw $failed }
Write-Host "BUILD/JVM GREEN. Physical-device checks remain pending." -ForegroundColor Green
