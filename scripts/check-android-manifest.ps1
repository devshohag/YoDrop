param(
    [string]$ManifestPath = (Join-Path (Split-Path $PSScriptRoot -Parent) "android/src/main/AndroidManifest.xml")
)
$ErrorActionPreference = "Stop"
[xml]$document = Get-Content -LiteralPath $ManifestPath -Raw
$android = "http://schemas.android.com/apk/res/android"
$root = $document.DocumentElement
function Require([bool]$condition, [string]$message) {
    if (-not $condition) { throw "Android manifest audit failed: $message" }
}
$permissions = @{}
foreach ($node in $root.SelectNodes("uses-permission")) {
    $permissions[$node.GetAttribute("name", $android)] = $node
}
foreach ($name in @(
    "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "WAKE_LOCK", "CAMERA", "VIBRATE",
    "BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS",
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE"
)) {
    Require ($permissions.ContainsKey("android.permission.$name")) "Missing permission $name"
}
foreach ($name in @("BLUETOOTH", "BLUETOOTH_ADMIN")) {
    Require ($permissions["android.permission.$name"].GetAttribute("maxSdkVersion", $android) -eq "30") "$name must end at API 30"
}
foreach ($name in @("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION")) {
    Require ($permissions["android.permission.$name"].GetAttribute("maxSdkVersion", $android) -eq "32") "$name must end at API 32"
}
foreach ($name in @("BLUETOOTH_SCAN", "NEARBY_WIFI_DEVICES")) {
    Require ($permissions["android.permission.$name"].GetAttribute("usesPermissionFlags", $android) -eq "neverForLocation") "$name must use neverForLocation"
}
foreach ($name in @("camera", "camera.any", "camera.front", "camera.autofocus")) {
    $features = @($root.SelectNodes("uses-feature") | Where-Object { $_.GetAttribute("name", $android) -eq "android.hardware.$name" })
    Require ($features.Count -eq 1) "Expected optional camera feature $name"
    Require ($features[0].GetAttribute("required", $android) -eq "false") "Camera feature $name must be optional for tap fallback"
}
$app = $root.SelectSingleNode("application")
Require ($null -ne $app) "Missing application"
Require ($app.GetAttribute("allowBackup", $android) -eq "false") "allowBackup must be false"
$services = @($app.SelectNodes("service") | Where-Object {
    $_.GetAttribute("name", $android) -match '(^|\.)TransferForegroundService$'
})
Require ($services.Count -eq 1) "Expected exactly one TransferForegroundService"
Require ($services[0].GetAttribute("exported", $android) -eq "false") "Transfer service must not be exported"
Require ($services[0].GetAttribute("foregroundServiceType", $android) -eq "connectedDevice") "Transfer service type must be connectedDevice"
Write-Host "Android manifest audit PASSED: $ManifestPath" -ForegroundColor Green
