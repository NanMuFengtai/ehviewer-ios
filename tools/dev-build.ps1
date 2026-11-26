#requires -Version 5.1
<#
  本地调试构建脚本（Windows）。
  用法：
    .\tools\dev-build.ps1                 # 构建 debug APK
    .\tools\dev-build.ps1 -Task testDebugUnitTest
    .\tools\dev-build.ps1 -Task lint
  依赖：JDK 25、Android SDK（platform 37.0 / build-tools 37.0.0 / NDK 29 / CMake 3.31.6）、Rust nightly-2026-08-22。
  工具链位置通过下面的默认值或环境变量指定。
#>
param(
    [string]$Task = ':app:assembleDebug',
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'

$toolchain = if ($env:EHVIEWER_TOOLCHAIN) { $env:EHVIEWER_TOOLCHAIN } else { 'D:\dev\android-toolchain' }
$jdk = Join-Path $toolchain 'jdk\jdk-25.0.5+8'
$sdk = Join-Path $toolchain 'sdk'
$gradle = Join-Path $toolchain 'gradle\gradle-9.7.1\bin\gradle.bat'

foreach ($p in @($jdk, $sdk, $gradle)) {
    if (-not (Test-Path $p)) { throw "工具链缺失：$p" }
}

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = Join-Path $toolchain 'gradle-home'
$env:CARGO_HOME = Join-Path $toolchain 'cargo'
$env:RUSTUP_HOME = Join-Path $toolchain 'rustup'
$env:Path = "$jdk\bin;$env:CARGO_HOME\bin;$sdk\platform-tools;$toolchain\gradle\gradle-9.7.1\bin;$env:Path"

$projectRoot = Split-Path -Parent $PSScriptRoot
Write-Host "JAVA_HOME   = $env:JAVA_HOME" -ForegroundColor Cyan
Write-Host "ANDROID_HOME= $env:ANDROID_HOME" -ForegroundColor Cyan
Write-Host "task        = $Task" -ForegroundColor Cyan

$args = @('--no-daemon', '--console=plain', '-p', $projectRoot, $Task)
if ($Offline) { $args = @('-offline') + $args }

& $gradle @args
$code = $LASTEXITCODE

# 出包后统一改名，产物固定为 out\很太之家.apk（安装后显示的名字同样是「很太之家」）
if ($code -eq 0 -and $Task -eq ':app:assembleDebug') {
    $apkDir = Join-Path $projectRoot 'app\build\intermediates\apk\debug'
    $apk = Get-ChildItem $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($apk) {
        $outDir = Join-Path $projectRoot 'out'
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
        # 文件名用码点拼，避免脚本在 PowerShell 5.1 下被按 ANSI 读取导致中文乱码
        $appName = -join ([char]0x5F88, [char]0x592A, [char]0x4E4B, [char]0x5BB6) # 很太之家
        $target = Join-Path $outDir "$appName.apk"
        Copy-Item $apk.FullName $target -Force
        Write-Host "APK -> $target ($([math]::Round($apk.Length / 1MB, 1)) MB, $($apk.Name))" -ForegroundColor Green
    }
}

exit $code
