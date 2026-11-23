$ErrorActionPreference = 'Stop'
$dotnet = Join-Path $env:USERPROFILE '.dotnet/dotnet.exe'
if (!(Test-Path $dotnet)) { $dotnet = 'dotnet' }
& $dotnet publish (Join-Path $PSScriptRoot 'Wirefree.App/Wirefree.App.csproj') `
    -c Release -r win-x64 --self-contained -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None `
    -o (Join-Path $PSScriptRoot 'dist')
if ($LASTEXITCODE -ne 0) { throw 'wirefree publish failed.' }
$source = Join-Path $PSScriptRoot 'setup'
$destination = Join-Path $PSScriptRoot 'dist/setup'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
if (Test-Path $source) {
    Get-ChildItem -LiteralPath $source -Filter '*.ps1' -File |
        Copy-Item -Destination $destination
}
