$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$projectFile = Join-Path $projectRoot 'src\CustomWindDown.App\CustomWindDown.App.csproj'
$publishDirectory = Join-Path $projectRoot 'artifacts\CustomWindDown-win-x64'
$archiveFile = Join-Path $projectRoot 'artifacts\CustomWindDown-0.1.0-win-x64.zip'

dotnet publish $projectFile -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -p:DebugSymbols=false -o $publishDirectory
if ($LASTEXITCODE -ne 0) { throw 'Publish failed.' }
Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination $publishDirectory
Compress-Archive -LiteralPath $publishDirectory -DestinationPath $archiveFile -Force
Write-Output $archiveFile
