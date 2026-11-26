#requires -Version 5.1
<#
  把 out\很太之家.apk 发布成 GitHub Release，供 App 的「檢查更新」读取。

  用法：
    .\tools\publish-release.ps1 -Notes "评论分批加载 / N 站标签样式"
    .\tools\publish-release.ps1 -Notes "..." -Force     # 同一构建重新上传

  Tag 规则：build-<git 最后一次提交时间戳>，和 APK 里的 BuildConfig.COMMIT_TIME 一致。
  所以发布前必须先把改动提交（工作树有未提交改动时脚本会提醒）。
#>
param(
    [string]$Notes = "",
    [string]$Repo = "NanMuFengtai/ehviewer-apk",
    [string]$Apk = "",
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Apk)) {
    $apkName = -join ([char]0x5F88, [char]0x592A, [char]0x4E4B, [char]0x5BB6) # 很太之家
    $Apk = Join-Path $root "out\$apkName.apk"
}
if (-not (Test-Path $Apk)) { throw "找不到 APK：$Apk" }

$dirty = (& git -C $root status --porcelain) -join ''
if ($dirty) {
    Write-Host "警告：工作树有未提交的改动，发布出去的包可能与 tag 对不上。" -ForegroundColor Yellow
}

$buildTime = (& git -C $root log -1 --format=%ct).Trim()
$commitSha = (& git -C $root log -1 --format=%h).Trim()
$tag = "build-$buildTime"
$sha = (Get-FileHash -Path $Apk -Algorithm SHA256).Hash.ToLower()
$sizeMb = [math]::Round((Get-Item $Apk).Length / 1MB, 1)

$body = @"
$Notes

SHA256: $sha
BUILD_TIME: $buildTime
COMMIT: $commitSha
"@

$notesFile = Join-Path $env:TEMP "ehviewer-release-$buildTime.md"
[System.IO.File]::WriteAllText($notesFile, $body, (New-Object System.Text.UTF8Encoding($false)))

$assetName = "EhViewer-$commitSha-arm64.apk"
# 注意：PowerShell 5.1 下原生命令往 stderr 写东西会触发 NativeCommandError，
# 这里临时把 ErrorActionPreference 放宽，只看退出码
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& gh release view $tag --repo $Repo *> $null
$exists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEap

if ($exists) {
    if (-not $Force) { throw "Release $tag 已存在；要覆盖上传请加 -Force" }
    Write-Host "覆盖上传到已有 Release $tag ..." -ForegroundColor Cyan
    & gh release upload $tag "$Apk#$assetName" --repo $Repo --clobber
} else {
    Write-Host "创建 Release $tag ..." -ForegroundColor Cyan
    & gh release create $tag "$Apk#$assetName" --repo $Repo --title "很太之家 $tag" --notes-file $notesFile
}
if ($LASTEXITCODE -ne 0) { throw "gh 发布失败（exit $LASTEXITCODE）" }

Write-Host ""
Write-Host "已发布：https://github.com/$Repo/releases/tag/$tag" -ForegroundColor Green
Write-Host "  tag        $tag"
Write-Host "  commit     $commitSha"
Write-Host "  APK        $sizeMb MB"
Write-Host "  SHA256     $sha"
Write-Host ""
Write-Host "手机打开 App → 设置 → 检查更新 即可看到这一版。"
