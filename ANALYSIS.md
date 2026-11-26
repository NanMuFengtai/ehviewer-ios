# EhViewer 项目拆解报告

> 分析对象：`D:\deepsee hs\工作区域\EhViewer`（浅克隆自 `ZhanZiyuan/EhViewer`，2026-11-19）
> 分析时间：2026-11-20

## 0. 来源与合法性状态

| 项 | 情况 |
| --- | --- |
| 上游 `github.com/FooIbar/EhViewer` | **已下架**：HTTP 451，`git ls-remote` 返回 `Repository unavailable due to DMCA takedown`（通知：`2026-03-23-fakku.md`） |
| 当前可用基座 | `ZhanZiyuan/EhViewer`：声明为「从 Software Heritage 存档恢复」，homepage 指向 FooIbar/EhViewer，41 stars，持续开发中 |
| 存档备份 | Software Heritage 有 10 次完整抓取，最新 snapshot `9d6082b8…`（2025-12-06），revision `ef4cb995…`（2024-10-18 最后提交） |
| 影响 | 无法从上游获取更新 / PR / issue；只能以这份恢复版为基线自己维护 |

## 1. 工程画像

- 语言：**Kotlin（402 个文件 / 约 3.7 万行） + Rust（39 个文件 / 约 5000 行）**，无 Java 源码
- 形态：Android 原生 App（E-Hentai/ExHentai 第三方客户端）+ Rust 原生库（JNI）
- 版本：`versionName 1.15.13`，`versionCode 180077`，`applicationId moe.tarsin.ehviewer`，`namespace com.hippo.ehviewer`
- 工具栏：Kotlin 2.4.10、AGP 9.3.1、Gradle 9.7.1、JDK 工具链 25（Android JVM target 17）、
  compileSdk/targetSdk 37、minSdk 26、NDK 29.0.14206865、Rust `nightly-2026-08-22`
- 关键依赖：Compose BOM 2026.08 / Compose Multiplatform 1.11.1、Material3 1.9、Ktor 3.5.2、
  OkHttp 5.5、Room 2.8.4、DataStore 1.2.1、Coil 3.5、Arrow 2.2（Result 型错误）、
  compose-destinations 2.3（导航代码生成）、Telephoto（缩放）、moko-resources（i18n）
- 构建侧：configuration cache + build cache + parallel、Spotless 格式化、Lint、ABI 拆分、
  R8 + 资源压缩、Baseline Profile、自定义约定插件（build-logic）

## 2. 模块结构

| 模块 | 文件 | 行数 | 职责 |
| --- | ---: | ---: | --- |
| `app` | 251 | 27545 | UI（Compose）、业务逻辑、网络、下载、阅读器、更新 |
| `core/common` | 26 | — | 通用工具、协程、日志等 |
| `core/data` | 40 | — | Room 数据库（画廊/下载/历史/收藏/过滤器/快速搜索）、DataStore 偏好、Cookie |
| `core/ui` | 58 | — | 可复用 Compose 组件、主题（material-kolor） |
| `core/i18n` | 0 kt | — | moko-resources 字符串（12 种语言） |
| `native` | 39 rs | 5026 | Rust 核心：归档读取、图片解码、HTML 解析、哈希、排序 |
| `build-logic` | 10 | 279 | Gradle 约定插件（Kotlin/Compose/Lint/Spotless/原生测试夹具） |
| `tools/verification` | 20 | 2244 | 发布校验工具：APK 身份/签名/JNI/ABI/16KB 对齐、快照打包、诊断 |
| `benchmark` | 3 | 130 | Macrobenchmark + Baseline Profile |

## 3. app 内部包职责

| 包 | 说明 |
| --- | --- |
| `client` | 网络与解析：`EhEngine`(451 行)、`EhUrl`、`ListUrlBuilder`、各页面 Parser（画廊列表/详情/评论/种子…）、错误类型体系 |
| `spider` | 页面调度与本地缓存：`SpiderQueen`(666)、`SpiderDen`(444)、`CbzArchive`、`ComicInfo`、速度统计 |
| `download` | 下载引擎：`DownloadManager`(822)、`DownloadService`(474)、队列/任务模型 |
| `reader` | 归档阅读：`NativeArchiveReader`、`ArchiveReader`（对接 Rust） |
| `jni` | Rust 绑定薄层：`Archive`、`GifUtils`、`Hash` |
| `ui` | Compose 界面：`MainActivity`、各 Screen（画廊列表/详情/下载/评论/设置…）、对话框 |
| `gallery`/`image`/`coil` | 图库模型、图片处理、Coil 集成 |
| `updater` | 版本检查与更新（GitHub Release / 快照通道） |
| `legacy` | 旧数据迁移 |
| `dailycheck`/`shortcuts`/`ktor`/`ktbuilder`/`util` | 签到、快捷方式、网络栈适配、DSL 工具 |

## 4. Rust 原生核心

- `native/rust/core/src/archive.rs`（1014 行）：ZIP/CBZ 读取（stored/deflate），带会话与缓存
- `parser/`（约 1200 行）：HTML 解析（detail 499、list 231、home、fav、torrent、profile、api）——**正在把 Kotlin 解析迁到 Rust**
- `img/`：WebP（libwebp-sys2）、二维码（rxing）、边框/区域拷贝
- `gif.rs`/`hash.rs`/`sort.rs`/`tl`（SIMD 排序）
- JNI 层 `src/ffi/`：`jvm.rs`(274)、`native.rs`(517)、`registry.rs`、`sessions.rs`
- 第三方补丁：`native/third-party/000{1,2,3}-*.patch`（zip_time 性能、bionic UTF-8、Cargo 输出路径）

## 5. 构建方式（CI 里的真实流程）

```
JDK 25（Temurin）→ Rust 工具链（rust-toolchain.toml，3 个 Android target）
→ cargo fmt / cargo test -p ehviewer_core / clippy（-D warnings）
→ ./gradlew -p tools/verification check installDist
→ ./gradlew :build-logic:convention:check spotlessCheck
→ ./gradlew assembleRelease / bundleRelease（ABI 拆分：arm64-v8a / armeabi-v7a / x86_64 / universal）
→ tools/verification 校验 APK 身份、签名、JNI、ABI、16KB 页对齐
```

本机现状：**Java 20（不够，需 25）、无 Android SDK、无 NDK、无 Rust、无 Android Studio**；磁盘 C 54.5 GB / D 139.8 GB 可用。
要本地能编译，需装：JDK 25、Android SDK Platform 37 + Build-Tools 37、NDK 29、Rust + 3 个 Android target，约 8–12 GB。

## 6. 可优化的方向（按证据排序）

### A. 体积
- ABI 三份 + Rust staticlib + 12 语言资源；可用 `-PreleaseAbi=arm64-v8a` 出单包，或做按需语言裁剪
- `packaging.resources.excludes` 已有优化，但可继续核查未使用资源（lint unused resources、`shrinkResources` 已开）
- 交付建议：arm64 单包 + universal 备用包两档

### B. 性能
- 启动：`MainActivity` 555 行、Application 初始化链；Baseline Profile 已有，可做启动 trace 对比
- 下载/阅读：`DownloadManager` 与 `SpiderQueen` 是并发热点，可加结构化并发 / 限流指标
- 图片：Rust 解码已用 SIMD 排序，可查 WebP/AVIF 解码路径与内存峰值（memmap2 + 流式）
- Compose 重组：大 Screen 文件（836/739/638 行）是重组热点候选，可用 Compose compiler metrics 定位

### C. 代码结构
- 超大文件拆分：`GalleryDetailContent.kt` 836、`DownloadManager.kt` 822、`Dialog.kt` 751、
  `DownloadsScreen.kt` 739、`SpiderQueen.kt` 666、`GalleryListScreen.kt` 638
- **双解析栈并存**（Kotlin `client/*Parser.kt` 与 Rust `parser/*`）：需定一条主路径，否则测试/维护双倍
- `app` 27.5k 行只按包分层，可按功能切子模块（download/reader/spider 已足够独立）

### D. 工程与质量
- `core/*` 的 `commonTest` 覆盖薄；`app` 单测 9 个、androidTest 5 个，UI 层几乎没有自动化测试
- `allWarningsAsErrors = true` 被注释掉；可分批打开
- `legacy` 迁移代码可设定下线节奏
- 发布通道：GitHub Actions 依赖原仓库的 secrets/签名（`.private-signing/`），换主维护需重建签名与发布流程

### E. 功能/定制（需要你定）
- 改包名/图标/名称做自己的分支（双开、避免与原版冲突）
- 加功能：更高并发下载、标签翻译、本地收藏增强、代理/Cronet 策略、阅读器手势等

## 7. 风险提示

1. 上游已下架，**没有官方更新渠道**；只能自维护或跟随其他活跃 fork
2. 项目依赖极新（AGP 9.3 / JDK 25 / Compose 2026.08），旧机器/旧 Android Studio 打不开
3. 签名密钥不在仓库（`.private-signing/` 本地目录），重新发布必须生成自己的 keystore
4. App 内容来自第三方站点，运行时强依赖网络环境与站点可用性
