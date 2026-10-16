$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$expected = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
$assetDirectory = Join-Path $projectRoot "android/src/main/assets"
$asset = Join-Path $assetDirectory "hand_landmarker.task"
function IsVerifiedModel([string]$path) {
    return (Test-Path -LiteralPath $path -PathType Leaf) -and ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $expected)
}
if (IsVerifiedModel $asset) { Write-Host "Hand model already verified"; return }
$modelCacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else {
    Join-Path ([Environment]::GetFolderPath("UserProfile")) ".gradle"
}
$cacheDirectory = Join-Path $modelCacheRoot "caches/YoDrop/models"
New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
$cached = Join-Path $cacheDirectory ("hand_landmarker-" + $expected + ".task")
if (-not (IsVerifiedModel $cached)) {
    $temporary = Join-Path $cacheDirectory ([guid]::NewGuid().ToString("N") + ".download")
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" -OutFile $temporary
        if (-not (IsVerifiedModel $temporary)) { throw "Downloaded hand model checksum mismatch" }
        Move-Item -LiteralPath $temporary -Destination $cached -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}
New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null
Copy-Item -LiteralPath $cached -Destination $asset -Force
if (-not (IsVerifiedModel $asset)) { throw "Packaged hand model checksum mismatch" }
Write-Host "Hand model verified and ready. App inference works offline." -ForegroundColor Green
