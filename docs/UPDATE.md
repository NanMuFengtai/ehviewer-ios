# 应用内更新（GitHub Release）

给「很太之家」加的自更新链路：App 读 GitHub Release → 下载 APK → 校验 → 交给系统安装器。

## 组成

| 文件 | 作用 |
| --- | --- |
| `app/src/main/kotlin/com/hippo/ehviewer/update/Updater.kt` | 查最新 Release、下载、sha256 校验、调安装器 |
| `app/src/main/kotlin/com/hippo/ehviewer/ui/screen/UpdateScreen.kt` | 「檢查更新」页：版本信息、更新源、检查、下载进度 |
| `tools/publish-release.ps1` | 把 `out\很太之家.apk` 上传成 GitHub Release |
| `Settings.updateRepo` | 更新源仓库，默认 `NanMuFengtai/ehviewer-apk`，可在设置页里改 |

入口：**设置 → 检查更新**（`settings_about_check_for_updates`，沿用已有字符串）。

## 版本怎么比

不看 `versionCode`，看构建时间：

- Release 的 tag 固定为 `build-<git 最后一次提交的时间戳>`
- App 里 `BuildConfig.COMMIT_TIME` 就是同一个时间戳
- `release.buildTime > BuildConfig.COMMIT_TIME` → 有新版本；相等 → 已是最新

这样不用每次手动改版本号，也不会出现「同一个包反复提示更新」。
代价：发布必须针对**已提交**的构建，tag 不能手写别的格式（脚本会自动取 `git log -1`）。

## 发布流程

```powershell
# 1. 提交改动（必须先提交，tag 取的是最后一次提交时间）
git add -A; git commit -m "..."

# 2. 出 arm64 交付包（脚本会自动放到 out\很太之家.apk）
.\tools\dev-build.ps1   # 或手动 gradle assembleDebug -PreleaseAbi=arm64-v8a

# 3. 发布
.\tools\publish-release.ps1 -Notes "本版更新内容写这里"
```

脚本会：算 sha256、写进 Release 说明（`SHA256: <hex>` 一行）、创建 tag `build-<ts>`、上传
`EhViewer-<sha>-arm64.apk`。同一个构建重复上传加 `-Force`。

## App 侧流程

1. 设置 → 检查更新 → 「檢查更新」
2. `GET https://api.github.com/repos/<repo>/releases/latest`（匿名，限流 60 次/小时）
3. 有新版本 → 显示标题、更新时间、更新说明、包大小
4. 「下載並安裝」→ 下载到 `cacheDir/update/update.apk`，进度条实时刷新，可取消
5. 校验：文件大小 + Release 说明里的 `SHA256`（不一致就删掉重来）
6. 调 `FileProvider` + `ACTION_VIEW` 打开系统安装器

## 发布仓库

- 仓库：`https://github.com/NanMuFengtai/ehviewer-apk`（public，只放安装包）
- **空仓库不能建 Release**：GitHub 会回 `422 Repository is empty`，先随便提交一个 README
  （已经做过；换仓库时要重做）
- 附件名用 `EhViewer-<sha>-arm64.apk`，中文文件名在下载 URL 里会被编码，容易出问题

## 已知限制

- **安装要手动允许一次**：系统会弹「允许安装未知应用」。
- **签名必须一致**：现在一直是 debug 签名，能覆盖装；换正式签名必须卸载重装。
- **国内网络**：`api.github.com` 和 release 下载可能慢或被墙。更新源可在设置页改成
  任意 `owner/repo`；如果以后换对象存储，把 `Updater.checkLatest` 换成读自定义 JSON 即可。
- **匿名限流**：每小时 60 次，正常使用（一天查几次）碰不到。
- Release 附件名用 ASCII（`EhViewer-<sha>-arm64.apk`），避免中文文件名的 URL 编码问题；
  App 端按 `.apk` 后缀取附件，文件名无所谓。
