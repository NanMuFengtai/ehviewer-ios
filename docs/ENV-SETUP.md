# 本机开发环境说明（Windows）

> 目标：在没有 Android Studio、也没有 WSL 的 Windows 机器上，把 EhViewer 编译出来。
> 实测：`assembleDebug` 通过，产出 `out/EhViewer-debug-arm64.apk`。

## 一、工具链位置

全部在 `D:\dev\android-toolchain`（纯英文路径，避免 NDK/CMake 处理中文路径出问题）：

| 组件 | 版本 | 路径 |
| --- | --- | --- |
| JDK | Temurin 25.0.5+8 | `jdk\jdk-25.0.5+8` |
| Android SDK | platform 37.0 / build-tools 37.0.0 / platform-tools 37.0.3 | `sdk` |
| NDK | 29.0.14206865 | `sdk\ndk\29.0.14206865` |
| CMake | 3.31.6（AGP 另用 3.22.1） | `sdk\cmake\3.31.6`、`sdk\cmake\3.22.1` |
| Gradle | 9.7.1 | `gradle\gradle-9.7.1` |
| Rust | nightly-2026-08-22（aarch64/armv7neon/x86_64 三个 Android target） | `rustup`、`cargo` |
| Gradle 缓存 | — | `gradle-home`（内含 `init.d\aliyun-mirrors.gradle` 镜像脚本） |

工程本身在 `D:\dev\EhViewer`（从 `ZhanZiyuan/EhViewer` 克隆，即 FooIbar/EhViewer 的存档恢复版）。

## 二、怎么构建

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\tools\dev-build.ps1                      # 编 debug APK
.\tools\dev-build.ps1 -Task testDebugUnitTest   # 跑单测
```

产物：`app\build\intermediates\apk\debug\app-arm64-v8a-debug.apk`（脚本会提示位置）。

只编 arm64 的开关放在 `gradle-home\gradle.properties`：
```
android.injected.build.abi=arm64-v8a
```
删掉这行就恢复三 ABI 全编（armv7/x86_64，时间约 3 倍）。

## 三、为了让它在 Windows 上编通，改了哪些地方

1. **`native/third-party/CMakeLists.txt`：libnettle 改为可选**
   原脚本在 Windows 上固定调 `wsl ./configure` 编 nettle（libarchive 的加密压缩包支持）。
   现在会实际执行 `wsl --list --quiet` 探测发行版，没有就跳过并打印警告。
   影响面：只影响“加密压缩包”，普通下载、阅读、归档、图片解码都不受影响。
   装了 WSL 之后自动恢复完整构建。

2. **`gradle-home\init.d\aliyun-mirrors.gradle`：依赖走阿里云镜像**
   Google Maven / Gradle Plugin Portal 在本机直连会挂起。镜像脚本在 settings 解析前注入阿里云仓库：
   插件用 `gradle-plugin`，依赖用 `google/central/public`。
   注意：别把 `gradle-plugin` 加进依赖仓库列表，它有些模块只镜像了 POM，会挡住真正的构件。

3. **SDK 包手工安装 + 补 `package.xml`**
   `platforms/android-37.0`、`ndk/29.0.14206865`、`cmake/3.31.6` 是从腾讯镜像下的 zip 解压的，
   官方 zip 里没有 `package.xml`，AGP 会认为“未安装”并重新下载。已按官方格式补齐。

4. **git 走 `ghproxy.net` 镜像**
   `git config --global url."https://ghproxy.net/https://github.com/".insteadOf "https://github.com/"`
   CMake FetchContent（libarchive/libwebp/xz/corrosion）和 cargo 的 git 依赖都靠它。

## 四、已知限制

- 首次构建要编译 liblzma / libarchive / libwebp / Rust，约 20–40 分钟；之后增量构建约 1 分钟。
- Debug APK 约 103 MB（含完整原生调试符号），Release 会小很多。
- 无 WSL 时**不支持加密压缩包**；需要的话装 WSL（`wsl --install`）后重新构建即可。
- 发布签名需要自己生成 keystore，仓库不带私钥。

## 五、验证记录

```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 52s
98 actionable tasks: 17 executed, 81 up-to-date
```

APK 校验（aapt2）：
```
package: name='moe.tarsin.ehviewer.debug' versionCode='180077' versionName='1.15.13-SNAPSHOT'
minSdkVersion:'26' targetSdkVersion:'37' compileSdkVersion='37'
lib/arm64-v8a/libehviewer.so  14.3 MB
```
