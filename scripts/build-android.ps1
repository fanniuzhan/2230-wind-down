param([switch]$IncludeAndroidTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot 'artifacts\android-toolchain'
$manifest = Join-Path $toolRoot 'toolchain.json'
if (-not (Test-Path -LiteralPath $manifest)) { throw 'Run scripts/bootstrap-android.ps1 -AcceptSdkLicenses first, or build android/ in Android Studio.' }
$toolchain = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
$env:JAVA_HOME = $toolchain.JavaHome
$env:ANDROID_HOME = $toolchain.SdkRoot
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-cache'
$gradle = Join-Path $toolchain.GradleHome 'bin\gradle.bat'
$tasks = @(':core:regressionTest', ':app:assembleDebug', ':app:lintDebug')
if ($IncludeAndroidTests) { $tasks += ':app:assembleDebugAndroidTest' }
& $gradle -p (Join-Path $projectRoot 'android') --no-daemon @tasks
if ($LASTEXITCODE -ne 0) { throw 'Android build, tests or lint failed.' }
$apk = Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$signer = Join-Path $toolchain.SdkRoot 'build-tools\36.0.0\apksigner.bat'
& $signer verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature validation failed.' }
$destination = Join-Path $projectRoot 'artifacts\CustomWindDown-android-0.1.3-debug.apk'
Copy-Item -LiteralPath $apk -Destination $destination -Force
$apkHash = Get-FileHash -LiteralPath $destination -Algorithm SHA256
$apkHash | Format-List
($apkHash.Hash.ToLowerInvariant() + '  ' + (Split-Path -Leaf $destination)) | Set-Content -LiteralPath ($destination + '.sha256') -Encoding ASCII
Copy-Item -LiteralPath (Join-Path $projectRoot 'android\README.md') -Destination (Join-Path $projectRoot 'artifacts\Android-使用说明.md') -Force
Write-Output $destination
& (Join-Path $PSScriptRoot 'package-android.ps1')
