param([switch]$AcceptSdkLicenses)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot 'artifacts\android-toolchain'
$downloads = Join-Path $toolRoot 'downloads'
New-Item -ItemType Directory -Path $downloads -Force | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-VerifiedArchive([string]$Url, [string]$Name, [string]$Hash, [string]$Algorithm) {
    $archive = Join-Path $downloads $Name
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host "Downloading $Name"
        $partial = $archive + '.download'
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            try { Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial; break }
            catch { if ($attempt -eq 3) { throw }; Write-Host "Retrying $Name ($attempt/3)" }
        }
        if ((Get-FileHash -LiteralPath $partial -Algorithm $Algorithm).Hash -ne $Hash) {
            throw "Checksum mismatch for $Name; partial file retained."
        }
        Move-Item -LiteralPath $partial -Destination $archive -Force
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash -ne $Hash) {
        throw "Checksum mismatch for cached $Name. Inspect that exact file before retrying."
    }
    return $archive
}

$jdkHome = Join-Path $toolRoot 'jdk-17.0.20.1+1'
if (-not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\javac.exe'))) {
    $jdkZip = Get-VerifiedArchive 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' 'temurin-17.0.20.1.zip' 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0' 'SHA256'
    Expand-Archive -LiteralPath $jdkZip -DestinationPath $toolRoot -Force
}
$gradleHome = Join-Path $toolRoot 'gradle-8.13'
if (-not (Test-Path -LiteralPath (Join-Path $gradleHome 'bin\gradle.bat'))) {
    $gradleHash = (Invoke-RestMethod -Uri 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256').Trim()
    $gradleZip = Get-VerifiedArchive 'https://downloads.gradle.org/distributions/gradle-8.13-bin.zip' 'gradle-8.13-bin.zip' $gradleHash 'SHA256'
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $toolRoot -Force
}
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$sdkManager = Join-Path $sdkRoot 'cmdline-tools\19.0\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $toolsZip = Get-VerifiedArchive 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' 'android-commandline-tools-19.zip' '54a582f3bf73e04253602f2d1c80bd5868aac115' 'SHA1'
    $toolsExtract = Join-Path $toolRoot 'commandline-19-extracted'
    Expand-Archive -LiteralPath $toolsZip -DestinationPath $toolsExtract -Force
    $toolsDestination = Join-Path $sdkRoot 'cmdline-tools\19.0'
    New-Item -ItemType Directory -Path $toolsDestination -Force | Out-Null
    foreach ($entry in @('bin', 'lib', 'source.properties', 'NOTICE.txt')) {
        Copy-Item -LiteralPath (Join-Path $toolsExtract "cmdline-tools\$entry") -Destination $toolsDestination -Recurse -Force
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\javac.exe'))) { throw 'Expected JDK directory not found.' }
$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-cache'
if ($AcceptSdkLicenses) {
    $licenseAnswers = 1..30 | ForEach-Object { 'y' }
    $licenseAnswers | & $sdkManager "--sdk_root=$sdkRoot" --licenses
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK license acceptance failed.' }
}
& $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK package installation failed.' }
foreach ($required in @('platforms\android-36\android.jar', 'build-tools\36.0.0\aapt2.exe', 'build-tools\36.0.0\apksigner.bat')) {
    if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot $required))) { throw "Android SDK installation is incomplete: $required" }
}
@{ JavaHome=$jdkHome; SdkRoot=$sdkRoot; GradleHome=$gradleHome } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $toolRoot 'toolchain.json') -Encoding UTF8
Write-Host 'Android toolchain is ready in artifacts/android-toolchain.'
