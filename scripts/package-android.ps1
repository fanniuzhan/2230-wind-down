$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceApk = Join-Path $projectRoot 'artifacts\CustomWindDown-android-0.1.2-debug.apk'
if (-not (Test-Path -LiteralPath $sourceApk)) { throw 'Run scripts/build-android.ps1 first.' }

# Verify the existing build before copying it into the phone handoff folder.
$sourceHash = (Get-FileHash -LiteralPath $sourceApk -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumFile = $sourceApk + '.sha256'
if (-not (Test-Path -LiteralPath $checksumFile)) { throw 'The APK build checksum is missing.' }
$recordedHash = ((Get-Content -LiteralPath $checksumFile -Raw).Trim() -split '\s+')[0]
if ($sourceHash -ne $recordedHash) { throw 'APK checksum mismatch. Rebuild before packaging.' }

$destination = Join-Path $projectRoot 'artifacts\手机App'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$phoneApk = Join-Path $destination '收工助手-0.1.2-安卓测试版.apk'
Copy-Item -LiteralPath $sourceApk -Destination $phoneApk -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'android\手机安装说明.md') -Destination (Join-Path $destination '先读我-手机安装说明.md') -Force
($sourceHash + '  ' + (Split-Path -Leaf $phoneApk)) | Set-Content -LiteralPath (Join-Path $destination 'SHA256.txt') -Encoding UTF8

$preview = Join-Path $projectRoot 'artifacts\Android-艺术版预览.png'
if (Test-Path -LiteralPath $preview) {
    Copy-Item -LiteralPath $preview -Destination (Join-Path $destination '界面预览.png') -Force
}
if ((Get-FileHash -LiteralPath $phoneApk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sourceHash) {
    throw 'Packaged APK checksum mismatch.'
}
Write-Output $phoneApk
