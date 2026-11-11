<#
.SYNOPSIS
  Motion Pointer PC 앱의 설치본·포터블·자동 업데이트 패키지를 만든다(Velopack).

.DESCRIPTION
  1. 자체 포함(.NET 설치 불필요) win-x64로 게시
  2. 이전 GitHub 릴리스를 받아 델타 업데이트를 만들 준비(-SkipDeltaDownload로 생략)
  3. vpk pack → <OutputDir>에 Setup.exe, Portable.zip, 전체/델타 nupkg, releases.win.json 생성

  만든 파일은 GitHub 릴리스에 모두 올려야 설치본의 자동 업데이트가 새 버전을 찾는다.
  준비물: .NET 10 SDK, vpk(`dotnet tool install -g vpk --version <Velopack 패키지 버전>`)

.EXAMPLE
  .\build-release.ps1 -Version 0.2.0
#>
param(
    [Parameter(Mandatory)] [string] $Version,
    [string] $OutputDir = (Join-Path $PSScriptRoot 'release'),
    [switch] $SkipDeltaDownload
)

$ErrorActionPreference = 'Stop'
$repoUrl = 'https://github.com/jeiel85/motion-pointer'
$project = Join-Path $PSScriptRoot 'src\MotionPointer\MotionPointer.csproj'
$publishDir = Join-Path $PSScriptRoot "publish\$Version"

# 앱이 표시하는 버전(csproj)과 패키지 버전이 어긋나지 않게 한다.
[xml] $xml = Get-Content $project
$projectVersion = @($xml.Project.PropertyGroup.Version | Where-Object { $_ })[0]
if ($projectVersion -ne $Version) {
    throw "csproj <Version>($projectVersion)과 -Version($Version)이 다릅니다. csproj를 먼저 올리세요."
}

if (Test-Path $publishDir) { Remove-Item $publishDir -Recurse -Force }
dotnet publish $project -c Release -r win-x64 --self-contained -p:DebugType=none -o $publishDir
if ($LASTEXITCODE) { throw "dotnet publish 실패 (exit $LASTEXITCODE)" }

New-Item -ItemType Directory -Force $OutputDir | Out-Null
if (-not $SkipDeltaDownload) {
    # 직전 릴리스의 전체 패키지를 받아 두면 vpk가 델타 패키지(작은 업데이트)를 만든다.
    vpk download github --repoUrl $repoUrl --outputDir $OutputDir
    if ($LASTEXITCODE) { throw "vpk download 실패 (exit $LASTEXITCODE)" }
}

vpk pack `
    --packId MotionPointer `
    --packVersion $Version `
    --runtime win-x64 `
    --packDir $publishDir `
    --mainExe motion-pointer.exe `
    --packTitle 'Motion Pointer' `
    --packAuthors jeiel85 `
    --icon (Join-Path $PSScriptRoot 'assets\motion-pointer.ico') `
    --shortcuts StartMenuRoot `
    --outputDir $OutputDir
if ($LASTEXITCODE) { throw "vpk pack 실패 (exit $LASTEXITCODE)" }

Get-ChildItem $OutputDir | Select-Object Name, Length
