param([string]$CacheRoot = (Join-Path ([IO.Path]::GetTempPath()) 'CustomWindDown-android-emulator'))
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot 'artifacts\android-toolchain'
$toolchain = Get-Content -LiteralPath (Join-Path $toolRoot 'toolchain.json') -Raw | ConvertFrom-Json
$CacheRoot = [IO.Path]::GetFullPath($CacheRoot)
$cacheDrive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($CacheRoot))
$needsExtraction = -not (Test-Path -LiteralPath (Join-Path $CacheRoot 'sdk\emulator\winddown-extraction-complete.txt')) -or
    -not (Test-Path -LiteralPath (Join-Path $CacheRoot 'sdk\system-images\android-36\default\x86_64\winddown-extraction-complete.txt'))
if ($needsExtraction -and $cacheDrive.AvailableFreeSpace -lt 8GB) { throw 'At least 8 GB free space is required in the emulator cache drive.' }
$emulatorSdk = Join-Path $CacheRoot 'sdk'
$downloads = Join-Path $CacheRoot 'downloads'
New-Item -ItemType Directory -Path $downloads -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $toolchain.SdkRoot 'licenses\android-sdk-license'))) {
    throw 'Run bootstrap-android.ps1 -AcceptSdkLicenses first.'
}

function Get-EmulatorArchive([string]$Url, [string]$Name, [string]$Sha1) {
    $archive = Join-Path $downloads $Name
    $oldArchive = Join-Path $toolRoot ('downloads\' + $Name)
    if (-not (Test-Path -LiteralPath $archive) -and (Test-Path -LiteralPath $oldArchive)) {
        if ((Get-FileHash -LiteralPath $oldArchive -Algorithm SHA1).Hash -eq $Sha1) {
            Copy-Item -LiteralPath $oldArchive -Destination $archive
        }
    }
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host "Downloading $Name from the official Android repository..."
        $partial = $archive + '.download'
        Invoke-WebRequest -Uri $Url -OutFile $partial -UseBasicParsing -TimeoutSec 1800
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA1).Hash -ne $Sha1) { throw "Checksum mismatch: $Name" }
        Move-Item -LiteralPath $partial -Destination $archive -Force
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA1).Hash -ne $Sha1) { throw "Invalid cached archive: $Name" }
    return $archive
}

function Expand-EmulatorArchive([string]$Archive, [string]$Destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $basePath = [IO.Path]::GetFullPath($Destination).TrimEnd('\') + '\'
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        foreach ($entry in $zip.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $basePath $entry.FullName))
            if (-not $target.StartsWith($basePath, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid archive entry path.' }
            if ($entry.Name -eq '') { [IO.Directory]::CreateDirectory($target) | Out-Null; continue }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally { $zip.Dispose() }
}

$emulatorMarker = Join-Path $emulatorSdk 'emulator\winddown-extraction-complete.txt'
if (-not (Test-Path -LiteralPath $emulatorMarker)) {
    $emulatorZip = Get-EmulatorArchive 'https://dl.google.com/android/repository/emulator-windows_x64-16428233.zip' 'emulator-37.2.12-windows.zip' '488ed747e82de7e9bb5247becd1ac043c7e5e85d'
    Write-Host 'Extracting the emulator...'
    Expand-EmulatorArchive $emulatorZip $emulatorSdk
    '37.2.12 / verified official archive' | Set-Content -LiteralPath $emulatorMarker -Encoding ASCII
}
$imageRoot = Join-Path $emulatorSdk 'system-images\android-36\default'
$imageMarker = Join-Path $imageRoot 'x86_64\winddown-extraction-complete.txt'
if (-not (Test-Path -LiteralPath $imageMarker)) {
    $imageZip = Get-EmulatorArchive 'https://dl.google.com/android/repository/sys-img/android/x86_64-36_r02.zip' 'android-36-aosp-x86_64-r02.zip' '829c076e8ff448a336097ae25a355b495ba36e2c'
    Write-Host 'Extracting the Android 16 test image...'
    New-Item -ItemType Directory -Path $imageRoot -Force | Out-Null
    Expand-EmulatorArchive $imageZip $imageRoot
    'API 36 r02 / verified official archive' | Set-Content -LiteralPath $imageMarker -Encoding ASCII
}
$commandlineDestination = Join-Path $emulatorSdk 'cmdline-tools\19.0'
if (-not (Test-Path -LiteralPath (Join-Path $commandlineDestination 'bin\avdmanager.bat'))) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $commandlineDestination) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $toolchain.SdkRoot 'cmdline-tools\19.0') -Destination $commandlineDestination -Recurse
}
if (-not (Test-Path -LiteralPath (Join-Path $emulatorSdk 'platform-tools\adb.exe'))) {
    Copy-Item -LiteralPath (Join-Path $toolchain.SdkRoot 'platform-tools') -Destination $emulatorSdk -Recurse
}
$emulatorPackagePath = Join-Path $emulatorSdk 'emulator\package.xml'
if (-not (Test-Path -LiteralPath $emulatorPackagePath)) {
    # Manual extraction needs the same generic local-package registration as sdkmanager.
    [xml]$packageMetadata = Get-Content -LiteralPath (Join-Path $toolchain.SdkRoot 'platform-tools\package.xml') -Raw
    $localPackage = $packageMetadata.SelectSingleNode('//*[local-name()="localPackage"]')
    $localPackage.SetAttribute('path', 'emulator')
    $localPackage.revision.major = '37'
    $localPackage.revision.minor = '2'
    $localPackage.revision.micro = '12'
    $localPackage.'display-name' = 'Android Emulator'
    $packageMetadata.Save($emulatorPackagePath)
}
@{ SdkRoot=$emulatorSdk; AvdRoot=(Join-Path $CacheRoot 'avd') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $toolRoot 'emulator.json') -Encoding UTF8
Write-Host "Official emulator and Android 16 image installed in $emulatorSdk."
