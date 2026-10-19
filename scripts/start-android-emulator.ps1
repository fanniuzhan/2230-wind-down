param([switch]$Setup, [switch]$Headless)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot 'artifacts\android-toolchain'
$manifest = Join-Path $toolRoot 'toolchain.json'
if (-not (Test-Path -LiteralPath $manifest)) { throw 'Run bootstrap-android.ps1 -AcceptSdkLicenses first.' }
$toolchain = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
$env:JAVA_HOME = $toolchain.JavaHome
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$adb = Join-Path $toolchain.SdkRoot 'platform-tools\adb.exe'
$imageId = 'system-images;android-36;default;x86_64'
$avdName = 'CustomWindDown_API36'
$serial = 'emulator-5580'

if ($Setup) {
    & (Join-Path $PSScriptRoot 'setup-android-emulator.ps1')
}
$emulatorManifest = Join-Path $toolRoot 'emulator.json'
if (-not (Test-Path -LiteralPath $emulatorManifest)) { throw 'Run this script with -Setup first.' }
$emulatorTools = Get-Content -LiteralPath $emulatorManifest -Raw | ConvertFrom-Json
$env:ANDROID_HOME = $emulatorTools.SdkRoot
$env:ANDROID_AVD_HOME = $emulatorTools.AvdRoot
$emulator = Join-Path $emulatorTools.SdkRoot 'emulator\emulator.exe'
$imageFile = Join-Path $emulatorTools.SdkRoot 'system-images\android-36\default\x86_64\system.img'
if (-not (Test-Path -LiteralPath $emulator) -or -not (Test-Path -LiteralPath $imageFile)) {
    throw 'Emulator is not installed. Run this script once with -Setup.'
}
& $emulator -accel-check
if ($LASTEXITCODE -ne 0) {
    throw 'Hardware acceleration is unavailable. Enable Windows Hypervisor Platform manually and reboot; this script will not modify Windows features.'
}
New-Item -ItemType Directory -Path $env:ANDROID_AVD_HOME -Force | Out-Null
$avdPath = Join-Path $env:ANDROID_AVD_HOME ($avdName + '.avd')
if (-not (Test-Path -LiteralPath (Join-Path $avdPath 'config.ini'))) {
    $avdManager = Join-Path $emulatorTools.SdkRoot 'cmdline-tools\19.0\bin\avdmanager.bat'
    'no' | & $avdManager create avd --name $avdName --package $imageId --path $avdPath --device pixel_7
    if ($LASTEXITCODE -ne 0) { throw 'Test AVD creation failed; existing files were retained.' }
    $configPath = Join-Path $avdPath 'config.ini'
    $configLines = Get-Content -LiteralPath $configPath | Where-Object { $_ -notmatch '^(disk.dataPartition.size|hw.ramSize|hw.cpu.ncore|showDeviceFrame)=' }
    @($configLines) + @('disk.dataPartition.size=2G','hw.ramSize=2048','hw.cpu.ncore=2','showDeviceFrame=no') |
        Set-Content -LiteralPath $configPath -Encoding ASCII
}
& $adb start-server
if ($LASTEXITCODE -ne 0) { throw 'ADB startup failed.' }
$listedDevice = @(& $adb devices | Where-Object { $_ -match '^emulator-5580\s' })
if ($listedDevice.Count -eq 0) {
    $configPath = Join-Path $avdPath 'config.ini'
    $configLines = Get-Content -LiteralPath $configPath | Where-Object { $_ -notmatch '^(hw.lcd.width|hw.lcd.height|hw.lcd.density|skin.name|skin.path)=' }
    @($configLines) + @('hw.lcd.width=720','hw.lcd.height=1600','hw.lcd.density=280','skin.name=720x1600','skin.path=_no_skin') |
        Set-Content -LiteralPath $configPath -Encoding ASCII
    $emulatorArgs = @('-avd', $avdName, '-port', '5580', '-gpu', 'auto', '-memory', '2048', '-no-boot-anim', '-no-snapshot')
    if ($Headless) { $emulatorArgs += '-no-window' }
    $logRoot = Join-Path $projectRoot 'artifacts\android-emulator'
    New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
    $launch = @{
        FilePath = $emulator
        ArgumentList = $emulatorArgs
        RedirectStandardOutput = (Join-Path $logRoot 'emulator-stdout.log')
        RedirectStandardError = (Join-Path $logRoot 'emulator-stderr.log')
        PassThru = $true
    }
    if ($Headless) { $launch.WindowStyle = 'Hidden' }
    $process = Start-Process @launch
    Write-Host "Starting dedicated test emulator (PID $($process.Id)); first boot can take a few minutes."
}
$ready = $false
for ($attempt = 0; $attempt -lt 240; $attempt++) {
    try { $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null }
    catch { $booted = '' }
    if ($booted -eq '1') { $ready = $true; break }
    if ($attempt % 20 -eq 0) { Write-Host 'Waiting for Android to boot...' }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Emulator boot timed out; inspect artifacts/android-emulator logs.' }
$actualName = @(& $adb -s $serial emu avd name)
if ($actualName -notcontains $avdName) { throw 'Port 5580 belongs to another emulator; no application was installed.' }
& $adb -s $serial shell wm size 720x1600
& $adb -s $serial shell wm density 280
$apk = Join-Path $projectRoot 'artifacts\CustomWindDown-android-0.1.0-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Build the APK first with build-android.ps1.' }
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed; application data was retained.' }
& $adb -s $serial shell am start -W -f 0x14000000 -n 'com.fanniuzhan.winddown/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Application launch failed.' }
Write-Host 'App opened on the dedicated Android 16 emulator. No physical phone or Windows wind-down setting was changed.'
Write-Host 'Accessibility and the night schedule are not enabled by this launcher.'
