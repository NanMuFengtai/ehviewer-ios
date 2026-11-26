# EhViewer 取图链路（站点 → 屏幕）

本文梳理「设置里的开关」到「一张图片真正显示在屏幕上」的完整路径，基于 `ZhanZiyuan/EhViewer` 1.15.13 源码。
所有行号对应当前仓库状态（commit `072d3fd` 之后）。

## 一、和取图有关的设置项

设置页共 9 个子页，真正影响取图的是下面 4 处。

### 1. 设置 → 画廊（`ui/settings/EhScreen.kt`）

| 设置项 | 键 | 作用 |
|---|---|---|
| 画廊站点 | `gallery_site` | 0 = e-hentai.org，1 = exhentai.org。切换站点等于切换**域名 + API + Referer/Origin**，见 `client/EhUrl.kt:55-108` |
| 列表模式 / 缩略图大小 | `list_mode` / `list_thumb_size` | 只影响列表 UI，不影响取图 |
| 显示画廊页数 / 阅读进度 | `show_gallery_pages` 等 | UI 层 |
| 过滤 / 标签翻译 | `eh_filter` / `tag_translations` | 影响列表过滤，不取图 |

注意：这个页面里**登录相关项（账号、站点切换、UConfig、我的标签）在未登录时整段隐藏**（`EhScreen.kt:78-134`），因为 ex 站点必须有 `igneous` cookie 才能返回内容。

### 2. 设置 → 下载（`ui/settings/DownloadScreen.kt`）

| 设置项 | 键 | 默认 | 作用 |
|---|---|---|---|
| 多线程下载 | `download_thread_2` | 3 | 同时下载的页数并发度 |
| 下载间隔 | `download_delay_3` | 1000 ms | 每页请求之间的延时（防站点限速） |
| 超时速度 | `timeout_speed` | — | 低于该速度判为超时，触发重试 |
| 原图下载 | `download_origin_image` | false | 走 `/fullimg/` 取原图（消耗 GP） |
| 保存为 CBZ | `save_as_cbz` | false | 先写临时目录，最后打包成 `<gid>.cbz` |
| 预加载页数 | `preload_image_2` | 5 | 阅读时前后预取的页数 |
| 下载位置 | `download_scheme` / SAF URI | — | 决定 `downloadLocation` |

### 3. 设置 → 高级（`ui/settings/AdvancedScreen.kt`）

| 设置项 | 键 | 作用 |
|---|---|---|
| 桌面版网站 | `desktop_site` | 换 User-Agent：桌面 Chrome UA / 移动 Chrome UA（`ktor/Common.kt:61-70`） |
| 启用 Cronet / QUIC | `enable_cronet` / `enable_quic` | 网络引擎换 Chromium 的 Cronet，走 HTTP/3 |
| 硬件位图阈值 | `hardware_bitmap_threshold` | 超过阈值的大图强制软件解码（省内存、防崩溃） |
| 激进预加载缩略图 | `preload_thumb_aggressively` | 列表滚动时提前加载缩略图 |
| 阅读缓存大小 | `read_cache_size_2` | 阅读时磁盘缓存页数 |
| 保存解析错误 body | `save_parse_error_body` | HTML 解析失败时把原始 body 存盘，便于修 native 解析器 |

### 4. 阅读器设置（`ui/reader/ReaderGeneralSetting.kt`、`ReaderModeSetting.kt`）

裁剪白边（`crop_borders`）、灰度/反色、阅读模式（左右翻页 / 上下滚动 / 双页）。这些都在**解码之后**生效。

### 没有的东西

全工程 `git grep -i proxy` **零命中**：这个版本没有 HTTP/SOCKS 代理设置项，只能依赖系统级 VPN / TUN。要加「自定义代理」是一个干净的切入点（见文末）。

## 二、取图链路

### 阶段 0：站点与身份

```
Settings.gallerySite ──► EhUrl.domain / apiUrl / referer / origin
                         (e-hentai.org 或 exhentai.org)

所有请求 ──► ktorClient
            ├─ HttpCookies → EhCookieStore → WebView CookieManager
            │    ipb_member_id + ipb_pass_hash 判登录；ex 需要 igneous
            ├─ UserAgent 插件 → Chrome UA（desktopSite 决定桌面/移动）
            ├─ Accept / Accept-Language 统一成 Chrome 的值
            └─ 每个请求单独带 Referer / Origin（EhRequestBuilder.kt:58-61）
```

- 身份 cookie 定义在 `core/data/.../EhCookieStore.kt`：`ipb_member_id`、`ipb_pass_hash`、`igneous`。
- 云盾（Cloudflare）判定：响应头 `cf-mitigated: challenge` → `CloudflareBypassException`（`EhEngine.kt:183`），由 WebView 过盾后拿 `cf_clearance`。

### 阶段 1：列表页 → 缩略图

```
GET https://e-hentai.org/...（Referer = 站点根）
  └─ GalleryListParser.parse(ByteBuffer)
       └─ Rust：native/rust/core/src/parser/list.rs（241 行，HTML → CBOR）
            └─ GalleryListResult { prev, next, galleryInfoList }
```

- 解析结果是 `GalleryInfo` 列表，缩略图是**绝对 URL**（指向 `ehgt.org` 图床），由 Coil 直接按 URL 加载。
- Coil 的 ImageLoader 在 `EhApplication.kt:189-228` 构建：自定义 `KtorNetworkFetcherFactory(httpClient = ktorClient)`，也就是**图片请求复用同一套 cookie/UA/Referer 逻辑**；磁盘缓存是 `thumbCache`。
- 已下载的画廊：`coil/DownloadThumbInterceptor.kt` 把请求改写到下载目录的 `thumb.jpg`，不走网络。
- 解码期拦截器链：`MergeInterceptor` → `DownloadThumbInterceptor` → `HardwareBitmapInterceptor` → `CropBorderInterceptor` → `DetectBorderInterceptor` → `QrCodeInterceptor` → `MapExtraInfoInterceptor` → WebP/GIF 解码器。

### 阶段 2：详情页 → 页数 + pToken

```
GET /g/{gid}/{token}/
  └─ GalleryDetailParser.parse
       └─ Rust：native/rust/core/src/parser/detail.rs（550 行）
            └─ GalleryDetail（页数、预览图 position + pToken）
```

- `SpiderQueen.readPreviews()`（`SpiderQueen.kt:332-343`）把预览里的 `pToken` 灌进 `SpiderInfo.pTokenMap`，同时算出 `previewPerPage`（每页预览图对应多少张正片）。
- 拿不全时有两个兜底：`/mpv/{gid}/{token}` 多页查看器（`GalleryMultiPageViewerPTokenParser`）、或按 `previewPerPage` 反推该翻哪个详情页。
- `SpiderInfo` 会落盘到下载目录的信息文件，也会进进程内缓存，下次打开不用重拉。

### 阶段 3：阅读器取图（核心）

入口：`ReaderScreen` → `gallery/EhPageLoader.kt` → `SpiderQueen.obtainSpiderQueen(info, MODE_READ)` → `PageLoader`。

`PageLoader`（`gallery/PageLoader.kt`）负责缓存与调度：

- 内存缓存 `SieveCache`：容量 = 总内存 / 8，夹在 256–512 MB 之间，按图片 allocationSize 计时。
- 预取窗口：`Settings.preloadImage`（默认前后 5 页）。
- 并发：信号量 4；每页一把 Mutex，防止重试和取消时两个任务同时写同一页。
- 引用计数：页面可见时 `pin()`，划走时 `unpin()`，被回收时释放。

`SpiderQueen.Worker.doInJob()`（`SpiderQueen.kt:608-721`）是真正的网络取图：

1. **已存在就跳过**：`index in spiderDen` → 直接 `STATE_FINISHED`。
2. **拿 pToken**：`pTokenMap` → `/mpv/` → 预览页，拿不到重试一次再报错。
3. **请求看图页**：`GET /s/{pToken}/{gid}-{n}?nl={skipHathKey}`，Referer = 详情页 URL。
4. **HTML 解析**（`client/parser/GalleryPageParser.kt`，Kotlin 正则，55 行）：
   - `<img src="..." style` → 正片 URL
   - `onclick="return nl('...')"` → `skipHathKey`（跳过 H@H 的密钥，代价 50 点）
   - `<a href=".../fullimg/...">` → 原图链接
   - `var showkey="..."` → API 模式用的 showkey
5. **API 兜底**：`showKey` 为空时走 `POST {apiUrl}`（gid / index / pToken / showkey / 上一页 pToken），返回 JSON 由 `GalleryPageApiParser` 解析（`EhEngine.kt:389`）。
6. **509 检测**：图片 URL 命中 `https://ehgt.org/509s.gif` 或 `exhentai.org/img/509.gif` → `QuotaExceededException`（`SpiderQueen.kt:726-731`）。
7. **下载图片**：`SpiderDen.makeHttpCallAndSaveImage()`（`SpiderDen.kt:199-214`）
   - 请求带 `Referer`（页面 URL）——这是必须的，没有它图床返回 403
   - 速度超时监控 `timeoutBySpeed` + `allowReadTransportRetry` 单次重试
   - 写 `.tmp` → 校验 URL 里的 SHA-1（`FileHashRegex`）→ 原子 rename 提交
8. **原图模式**：`EhEngine.getOriginalImageUrl()`（`EhEngine.kt:195-202`）用 **no-redirect** 客户端请求 `/fullimg/` 链接，从 302 的 `Location` 拿真实图床地址（需要 GP）；失败时补 `?nl=skipHathKey` 再试一次。
9. **落盘位置**：
   - 阅读模式 → `sCache`（磁盘缓存）+ 下载目录里已有的文件
   - 下载模式 → `downloadLocation/<画廊目录>/`；开了 CBZ 就先写临时目录，最后打包

上屏：

```
SpiderQueen 通知 onPageReady
  └─ PageLoader.notifySourceReady(index)
       └─ Image.decode(source)   // image/Image.kt:125
            ├─ PathSource → 文件描述符读取；GIF 在 API 29 以下走 mmap + rewrite
            └─ Coil 解码：allowHardware / 阈值 / 裁剪白边 / 二维码检测
                 └─ PageStatus.Ready(image) → PagerItem 绘制
```

### 阶段 4：批量下载

`DownloadManager` 复用同一套 `SpiderQueen`，只是切到 `MODE_DOWNLOAD`：

- 并发度 `download_thread_2`（默认 3），每页间隔 `download_delay_3`（默认 1000 ms）
- 目录模式直接写文件；CBZ 模式写临时目录 → `jni.beginZipWriter/appendZipEntry` 打 zip
- 完成后写 `DownloadInfo` 进 Room，供下载页展示

## 三、错误与边界

| 现象 | 处理 |
|---|---|
| `cf-mitigated: challenge` | `CloudflareBypassException`，转 WebView 过盾 |
| 被重定向到登录页 | `NotLoggedInException`（`EhEngine.kt:181`） |
| 509 配额图 | `QuotaExceededException`，提示等重置或买 GP |
| `/fullimg/` 没有 Location | `InsufficientGpException` |
| HTML 解析失败 | `ParseException`，可选落盘原始 body（`save_parse_error_body`） |
| 图片被判定为广告 | `detectAds` → `PageStatus.Blocked`，显示占位图 |
| 内存紧张 | `onTrimMemory` 清 Coil 内存缓存 + 阅读器空闲图 + 归档缓存 |

## 四、加功能可用的切入点

1. **代理**：目前没有。可在 `ktor/Common.kt` 的 `configureCommon` 里加 `engine { proxy = ProxyConfig(...) }`，并在设置里加一项；Cronet 分支要单独处理。
2. **取图限速 / 并发**：`PageLoader.semaphore = Semaphore(4)` 是写死的，可提到设置项。
3. **图床优选**：`imageUrl` 来自看图页 HTML，H@H 节点不可控；可在下载前按域名测速挑节点。
4. **解析器收敛**：`GalleryPageParser` 是 Kotlin 正则，列表/详情是 Rust，双栈并存——要改解析行为需要两处都懂。
5. **缩略图缓存清理 / 预加载策略**：`thumbCache` + `preload_thumb_aggressively`，改起来风险低。
