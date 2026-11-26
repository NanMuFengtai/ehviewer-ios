# 多内容源：E-Hentai + nhentai

本轮实际改了的东西和使用说明。方案背景见 [MULTI-SITE-PLAN.md](MULTI-SITE-PLAN.md)。

## 做了什么

1. **抽屉顶部加了站点胶囊**：左边 `E`（E-Hentai），右边 `N`（nhentai），指示块滑动切换。
2. **侧栏跟着站点变**，切换时列表左右滑入滑出。
3. **nhentai 只读链路可用**：首页（最新）、热门、标签、搜索、详情、阅读器。
4. **E 站一行没动**：内容源默认还是 E 站，原有 8 个入口、历史、收藏、下载、阅读器全部照旧。

侧栏对照：

| E 站 | nhentai |
|---|---|
| 主页 | 首页（最新） |
| 订阅 | 热门 |
| 热门 | 标签 |
| 排行 | — |
| 收藏 | — |
| 历史 | 历史（只显示 E 站记录） |
| 下载 | 下载（只显示 E 站任务） |
| 设置 | 设置 |

nhentai 没有的入口不显示，不摆空壳。

## 代码落在哪

| 文件 | 作用 |
|---|---|
| `Sites.kt` | 内容源常量 `Sites.EH` / `Sites.NHENTAI` |
| `Settings.kt` | 新增 `content_source` 偏好（默认 0 = E 站） |
| `nhentai/NhentaiApi.kt` | v2 接口、数据模型、图床域名与双扩展名规范化 |
| `nhentai/NhentaiImages.kt` | 图片磁盘缓存：`cacheDir/nhentai/<id>/<页码>.<ext>`，先写 `.part` 再原子改名 |
| `nhentai/NhentaiPageLoader.kt` | 复用 E 站的 `PageLoader`（内存缓存、前后预取、解码、翻页全通用），只替换"这一页从哪来" |
| `ui/main/SiteSwitcher.kt` | 胶囊开关（滑动指示块） |
| `ui/screen/nhentai/NhentaiListScreen.kt` | 列表 + 搜索 + 标签页 |
| `ui/screen/nhentai/NhentaiDetailScreen.kt` | 详情（封面、标题、页数、标签、开始阅读） |
| `ui/MainActivity.kt` | `navItemsOf()` / `defaultDirectionOf()`、抽屉里的胶囊与 AnimatedContent |
| `ui/reader/ReaderScreen.kt` | 新增 `ReaderScreenArgs.Nhentai` 与加载器分支 |

## 取图链路（nhentai 侧）

```
列表   GET /api/v2/galleries?page=n           → 列表项（缩略图路径 + 页数）
热门   GET /api/v2/galleries/popular          → 单页数组
标签   GET /api/v2/galleries/tagged?tag_id=   → 列表
搜索   GET /api/v2/search?query=&sort=date    → 列表
详情   GET /api/v2/galleries/{id}             → pages[] 全量页表
图床   GET /api/v2/cdn                        → i1-i4 / t1-t4，失败用内置默认值
图片   {image_server}/{pages[i].path}         → 直接下，不需要 Referer
```

和 E 站的关键差别：**没有 pToken、没有逐页 HTML 解析、不需要 Referer**。
详情这一次请求就把全书页表拿全了，阅读器只需要按 URL 拉字节。

## 已知限制（这一轮不做的）

1. **nhentai 不进数据库**：历史、收藏、下载管理仍然只认 E 站。跨站数据隔离（给 `GalleryEntity` 加 `SITE` 列 + 复合主键 + Room 迁移）留到下一轮，那是个会动 DAO 和迁移的活。
2. **nhentai 没有"随机"入口**，也没有标签分类浏览（只列热门 tag）。
3. **标签是英文原文**，E 站的标签翻译库不适用。
4. **网络**：nhentai 挂 Cloudflare。实测本机直连 403、走代理 200。工程里没有代理设置项，得靠系统级 VPN/TUN。
5. 阅读时不写进度，退出再进从头开始。

## 界面重做（对齐 E 站观感）

### 列表页

- 顶栏换成 E 站同款的可展开悬浮搜索条（直接复用 `ui/screen/SearchBarScreen.kt`）：
  点整条展开成全屏搜索，输入关键词回车即搜，标题实时变成搜索词
- 网格固定三列，封面 0.72 比例裁剪，标题两行 + 页数
- 保留下拉无限加载、加载中指示、错误重试

### 详情页

照禁漫详情页（E 站 + 网页版）那套版式重排，两者保持一致的手感：

```
[封面 116dp]  标题（最多 4 行）
              日文原名（小字，可选）
              作者：A / B            ← 靠右，多个作者斜杠分隔、各自可点
              ♥ 18.1K 收藏
              164 頁
              #123456
              上傳於 2024-05-01
[下載] [收藏] [Read page 2]         ← 阅读最右，主按钮
標籤（E 站同款：每组一行，左边类型胶囊蓝底、右边标签值胶囊粉底自动换行，
     点标签进该标签列表）
預覽（三列缩略图，右下角页码，点第 N 张直接进阅读器第 N 页）
```

改动要点（2026-11-23）：

- 头部数字从原来按钮下面的一行「页数 / 收藏 / 上传于」改成**四行靠右**：收藏 / 页数 / 编号 / 上传日期，
  收藏数用 `formatCount()` 格式化成 `18.1K`
- 作者从原来的分类/语言/作者/社团/原作 chips 行里提出来，单独一行靠右文字，
  `artist` 标签每个一个可点 `Text`，点哪个搜哪个（`group` 没有 artist 时兜底显示）
- 按钮顺序改成 **下載 → 收藏 → 阅读**（原来是 收藏 → 下载 → 阅读），阅读仍在最右
- 封面 132dp → 116dp，和禁漫详情页一致
- 标签样式改成和 E 站一模一样：每组一行 `Row { 类型胶囊 + FlowRow { 值胶囊 } }`，
  类型用 `primaryContainer`（浅蓝）、值用 `tertiaryContainer`（浅粉）、形状 `RoundedCornerShape(64.dp)`，
  内边距 `horizontal 12.dp / vertical 4.dp`，对应 E 站 `GalleryTag.kt` 的 `BaseRoundText`
  （nhentai 的 `Tag` 和 E 站的 `GalleryTagGroup` 不是同一个模型，所以这里按同样参数重写了 `TagPill`，
  没有直接复用 `GalleryTags`）

回滚办法：

```
git tag nhentai-detail-before-20261123     # 改动前的完整工程快照
git checkout nhentai-detail-before-20261123 -- \
  app/src/main/kotlin/com/hippo/ehviewer/ui/screen/nhentai/NhentaiDetailScreen.kt
```

旧文件副本在 `out/backup-20261123/NhentaiDetailScreen.kt`。

### 图片容错

图床按 i1 → i2 → i3 → i4 依次重试，单台超时自动换下一台；
封面下载全挂时回退到缩略图，不出现裂图。

## nhentai 登录与收藏

### 站点怎么验证的

v2 的登录接口 `POST /api/v2/auth/login` 要 5 个字段：`username`、`password`、`pow_challenge`、`pow_nonce`、`captcha_response`。
最后一个是 **Cloudflare Turnstile** 签名的，只有真实浏览器环境才拿得到（`GET /api/v2/captcha` 返回 `provider=turnstile` + `site_key`）。

所以纯接口登录不可行，客户端走 **WebView 登录**：加载 `https://nhentai.net/login`，用户自己输账号密码、过验证码；
页面登录成功后 cookie 里会出现 `access_token` 和 `refresh_token`，app 从 WebView 的 Cookie 里取出来存进 DataStore。

### 鉴权方式（实测确定）

网页版点收藏时发的是：

```
POST /api/v2/galleries/{id}/favorite
Authorization: User <access_token>
```

不是 `Bearer`，也不是 `Key`（这两种服务端会回 `Malformed Authorization header`）。

| 接口 | 用途 |
|---|---|
| `GET /api/v2/user` | 取当前账号（用于校验 token） |
| `GET /api/v2/favorites?page=n` | 收藏列表（分页） |
| `GET /api/v2/galleries/{id}/favorite` | 查询是否已收藏 |
| `POST / DELETE /api/v2/galleries/{id}/favorite` | 收藏 / 取消收藏 |

token 寿命：`access_token` 约 2 小时，`refresh_token` 30 天。
401 时自动用 `POST /api/v2/auth/refresh` 换新的一对；refresh 也失效就清空登录态，让用户重新登录。

### app 里的入口

- 设置 → **nhentai account**：未登录时内嵌站点登录页，登录成功自动变成账号页（显示用户名 + 退出按钮）
- 账号页右上角**钥匙图标**：可以手动粘贴 `access_token` 或整段 Cookie（WebView 被验证码卡住时的兜底，也方便从电脑浏览器搬 token）
- nhentai 侧栏新增**收藏**入口：未登录显示登录按钮，登录后显示账号的收藏列表（三列网格）
- 详情页「开始阅读」右边是**收藏按钮**，已收藏显示实心红心 + Favorited

## 安装名与产物名

- **安装后显示名**：很太之家
  - `app/src/debug/res/values/strings.xml` 的 `app_name`（debug 包实际生效的就是它）
  - `core/i18n/.../base/strings.xml` 的 `app_name`（release 包用这个）
  - `translatable="false"`，所有语言下都显示中文名
- **产物文件名**：`out\很太之家.apk`，由 `tools/dev-build.ps1` 在编译成功后自动改名
- **包名没动**：仍是 `moe.tarsin.ehviewer.debug`，所以覆盖安装、保留数据升级都正常

脚本里改用码点拼中文文件名（`[char]0x5F88…`），因为 PowerShell 5.1 读 `.ps1` 按 ANSI，直接写中文会变成乱码文件名。

## 怎么验证

编译：

```powershell
D:\dev\EhViewer\tools\dev-build.ps1
```

产物在 `app/build/intermediates/apk/debug/`，会另存一份到 `out/`。

接口自检（不需要设备）：

```powershell
$env:HTTPS_PROXY='http://127.0.0.1:7890'
curl.exe -s -A "Mozilla/5.0" "https://nhentai.net/api/v2/galleries?page=1&per_page=2"
curl.exe -s -A "Mozilla/5.0" "https://nhentai.net/api/v2/galleries/698736"
curl.exe -s -A "Mozilla/5.0" "https://nhentai.net/api/v2/cdn"
```

## 验证记录（2026-11-20，Android 14 x86_64 模拟器实跑）

模拟器：`system-images;android-34;google_apis;x86_64`，AVD 名 `nhtest`，走 `-http-proxy http://127.0.0.1:7890`。

| 步骤 | 结果 |
|---|---|
| 抽屉顶部胶囊 | E 选中时深色指示块在左，点 N 后滑到右，侧栏换成 6 项 |
| E 站回归 | 访客模式进站，首页列表正常加载 |
| 切到 N | 抽屉自动关闭并切到 Latest 列表，封面与页数都正确 |
| 详情 | 标题 Aunt Hina、164 pages、384 favorites、5 个标签、封面正常 |
| 阅读 | 点 Start reading 进阅读器，页 1/164 正常渲染，缓存落在 `cache/nhentai/698740/N.webp` |
| 重启 | 杀掉进程重启，直接回到 nhentai Latest（站点选择已持久化） |

**踩到的坑（已修）**：详情页封面用 Coil 的 Ktor fetcher 会报
`CronetUrlRequest: net::ERR_HTTP2_PROTOCOL_ERROR`（Cronet + 代理的 HTTP/2 问题），
改成用自建下载器把封面拉到缓存再显示，失败回退缩略图。页面正文图片一直走自建下载器，不受影响。

证据截图在 `out/screens/`：`s4.png`（E 侧栏）、`s5.png`（切到 N 的列表）、
`t3.png`（详情）、`s10.png`（阅读器 1/164）、`g_5.png`（N 侧栏）。

## 模拟器怎么复现

```powershell
$env:ANDROID_HOME='D:\dev\android-toolchain\sdk'
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd nhtest -no-window -no-audio -no-boot-anim `
    -gpu swiftshader_indirect -http-proxy http://127.0.0.1:7890
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r -t out\EhViewer-multisite-x86_64.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n `
    moe.tarsin.ehviewer.debug/com.hippo.ehviewer.ui.MainActivity
```

注意 APK 是 `test-only`，安装必须带 `-t`。

## 2026-11-20 第二轮修复：返回刷新 / 返回闪退 / 共享元素动画

### 问题 1：从详情返回，列表刷新回顶部

原因：列表状态原先用 `remember { NhentaiListState(args) }` 存在 Composable 里，
跳详情时列表离开组合被销毁，返回时 `LaunchedEffect(state) { load(true) }`
又从头拉第一页 —— 数据重新加载，`LazyVerticalGrid` 的滚动位置因为列表项还没回来也被清掉。

修法：状态搬进 `NhentaiListViewModel`（`ui/screen/nhentai/NhentaiListViewModel.kt`），
作用域是导航返回栈的那条记录。返回时 ViewModel 还在，列表原样复用，
滚动位置由 `rememberLazyGridState` 的 saveable 状态恢复。
`ensureLoaded()` 只在第一次进入时请求第一页。

### 问题 2：返回时偶发闪退

logcat 抓到的真实栈：

```
java.lang.IllegalArgumentException: Key "698752" was already used.
If you are using LazyColumn/Row please make sure you provide a unique key for each item.
  at androidx.compose.ui.layout.LayoutNodeSubcompositionsState.subcompose
  at androidx.compose.foundation.lazy.grid.LazyGridMeasureKt.measureLazyGrid
```

原因：nhentai 的列表分页会随时间漂移（新本子一直在往第一页插），
翻第二页时服务器可能把第一页已经出现过的本子再给一次，
`items(state.items, key = { it.id })` 就出现两个相同 id，Compose 直接抛异常。

修法：追加分页时按 id 去重（`distinctBy { it.id }`），去重数量写进日志；
另外加载失败时把 `hasMore` 置 false，避免在错误页上反复拉取。

### 问题 3：返回缺少动画

E 站的列表封面和详情封面是同一套共享元素（`SharedElementBox` + `SETNodeGenerator` 互连），
返回时封面从详情位置缩回卡片。nhentai 侧照抄：

- `ui/screen/nhentai/NhentaiShared.kt`：`nhentaiListThumbs connectTo nhentaiDetailThumbs`，
  在 `EhApplication.onCreate` 里 `initNhentaiSETConnection()`
- 列表卡片封面 `SharedElementBox(key = "nhentai-<id>")`
- 详情页头部封面同一个 key

### 顺手修掉的

- `NhentaiImages` 的下载与落盘改到 `Dispatchers.IO`（之前在组合线程做磁盘 IO，StrictMode 一直在报）
- 封面下载失败时把具体异常写进日志，并追加缩略图服务器作为最后一档回退
- 取消协程时不再被 `runCatching` 吞掉（`CancellationException` 重新抛出）

### 验证记录

| 检查项 | 结果 |
|---|---|
| 滑到第 6 屏后点开本子再返回 | 可见条目与返回前完全一致，没有重新拉第一页 |
| 连续 12 次「点开→返回」 | 进程 pid 不变，无 FATAL，日志里 12 条 `detail ... loaded` |
| 共享元素 | 慢放（`animator_duration_scale 6`）抓到返回过渡帧，列表侧滑入、封面回到卡片 |
| 日志目录 | 选 `Download/hentailogs` 后写入 `很太之家-20261120.log`，无重复文件 |
| 崩溃导出 | `adb shell am crash` 后目录里出现 `crash-20261120-223322.log`，含设备信息 + 栈 + 最近日志 |

## 2026-11-20 第三轮：登录入口进搜索栏 / nhentai 独立历史

### 登录入口

E 站搜索栏右上角是账号按钮（未登录时是 NoAccounts 图标），nhentai 照搬：
`NhentaiListScreen` 的 `trailingIcon` 里，刷新按钮右边加一个账号按钮，
登录态用 `Settings.nhentaiAccessToken.collectAsState()` 订阅，登录/退出后图标立刻变，
点击直接进 `NhentaiAccountScreen`（未登录时就是站点登录页）。

### nhentai 历史

之前 nhentai 侧栏的「历史」打开的是 **E 站历史**（`HistoryScreenDestination`），所以永远空的。
现在：

- 新增 `nhentai/NhentaiHistory.kt`：JSON 落盘 `filesDir/nhentai/history.json`，
  最多 500 条，按 id 去重，重复打开同一个本子会把它移到最前面并更新时间
- 新增 `ui/screen/nhentai/NhentaiHistoryScreen.kt`：搜索栏（可按标题过滤）+ 三列缩略图行，
  点进详情、右侧叉号删单条、右上角垃圾桶清空
- 详情页 `NhentaiDetailScreen` 拉取详情成功后写一条历史（标题、缩略图、页数、时间）
- `MainActivity.nhentaiNavItems` 的「历史」指向 `NhentaiHistoryScreenDestination`

不进 Room 的原因：nhentai 的 id 和 E 站 gid 都是纯数字，混表要改主键 + 迁移，
单独 JSON 不会影响 E 站历史/收藏/下载任何既有逻辑。

### 验证记录

| 检查项 | 结果 |
|---|---|
| 搜索栏账号图标 | 已登录显示人像、未登录显示 NoAccounts，点击进账号页 |
| 打开本子后看历史 | 侧栏历史里出现条目：缩略图 + 标题 + `14P · 2026-11-20 23:24` |
| 历史点进详情 | 正常打开；返回后该条目时间更新并置顶 |
| 删除单条 | 叉号删除后条目消失，`history.json` 变 `[]` |
| 清空全部 | 垃圾桶 → 确认框 → 列表变空 |
| 旋转屏幕 | 列表内容与滚动位置都保持（ViewModel + saveable 状态） |
| E 站回归 | 切回 E 站首页、历史页均正常，E 站历史仍是自己的数据 |

## 2026-11-21 第四轮：详情缓存 / 阅读进度 / nhentai 离线下载

### 详情缓存

之前每次进详情（包括从阅读器返回、从历史再点进来）都会重新请求一次详情接口，
日志里能看到连续好几条 `detail ... loaded`。现在：

- `nhentai/NhentaiDetailCache.kt`：内存 LRU 12 条、30 分钟过期
- 详情页先查缓存，命中直接渲染（日志 `detail xxx from cache`），不走网络
- 阅读器 `useNhentaiPageLoader` 也用同一份缓存，打开阅读器不再重复拉详情
- 点错误页的重试会绕过缓存强制刷新

### 阅读进度

- `PageLoader` 新增 `saveProgress()` + 可覆盖的 `saveReadProgress(index)`；
  E 站仍然写 Room 的 progress 表，nhentai 覆盖成写自己的历史 JSON
- `ReaderScreen` 加了 `DisposableEffect(pageLoader) { onDispose { pageLoader.saveProgress() } }`，
  退出阅读器就把「看到第几页」写回去
- 历史条目多了 `lastPage`；重新打开同一本不会把进度清零（`record()` 合并旧值）
- 详情页按钮变成「继续阅读 13/164」，预览网格里读过的页页码变主题色
- 历史行显示 `13/164 · 时间`；列表卡片在设置里开了「显示阅读进度」后显示 `13/164P`

### nhentai 离线下载

E 站的下载管理只认 E 站的书，nhentai 之前点侧栏「下载」进的是 E 站页面，等于没有。现在：

- `nhentai/NhentaiDownloads.kt`：`filesDir/nhentai/downloads/<id>/<页码>.<ext>` + `downloads.json` 索引
  - 整份详情一起存进去，所以离线时连接口都不用请求
  - 并发数跟设置里的下载并发一致（默认 3），断点续传（已存在的页跳过）
  - 进度实时写进 StateFlow，每 4 页落一次盘
- 详情页多了一行：`下载全部（N 页）` → 下载中转圈+进度条 → `已下载 · 离线可读`；
  中途断掉显示 `未完成 x/N` + 继续下载
- 侧栏「下载」指向新的 `NhentaiDownloadsScreen`：缩略图、标题、状态、进度条、删除；
  点已完成的直接进阅读器（带进度续读），点未完成的继续下载
- 阅读器取图顺序：已下载文件 → 阅读缓存 → 网络

### 验证记录

| 检查项 | 结果 |
|---|---|
| 详情缓存 | 返回列表再进同一本，日志为 `detail 698803 from cache`，无网络请求 |
| 阅读进度 | 从预览点第 2 页进阅读器再返回，`history.json` 里出现 `"lastPage":1`，详情显示 `Continue 2/2` |
| 进度不被覆盖 | 再次打开同一本详情，历史条目进度仍在 |
| 历史/列表展示 | 历史行显示 `2/2 · 时间` |
| 下载 | 26 页并发下载约 90 秒完成，详情变 `Downloaded · readable offline` |
| 断点续传 | 杀进程后重进详情显示 `Incomplete x/26` + 继续下载，续传只补缺的页 |
| 离线阅读 | 关掉代理（`http_proxy :0`）后从下载页进阅读器，第 1 页正常渲染 |
| 下载页 | 列表显示缩略图、`Downloaded · readable offline`、删除按钮 |

## 2026-11-21 第五轮：返回详情不重载 / 三按钮布局

### 返回详情页不再转圈

之前从阅读器返回，详情页要转一下圈：详情状态存在 Composable 里，进阅读器就被销毁，
回来时只能靠内存缓存重新走一遍加载流程（缓存过期就是真请求）。

现在状态搬到 `NhentaiDetailViewModel`（导航返回栈作用域）：

- 进阅读器再返回，ViewModel 还活着，详情/封面/收藏态直接复用，**第一帧就是完整详情**
- `ensureLoaded()` 只会跑一次，返回时根本不进加载流程（日志里连 `from cache` 都不打）
- 只有换书、或者点错误页重试才会重新请求

实测：从阅读器按返回，450ms 时截图已经是完整详情页，没有加载圈；日志无新请求。

### 详情行动区改成三按钮

`收藏 | 下载 | 阅读` 并排（原来下载是单独一整行；阅读放最右手边，单手握着手机时拇指正好落在上面）：

| 状态 | 中间按钮显示 |
|---|---|
| 没下载 | 下载图标 + `下载` |
| 下载中 | `12/239`（实时进度） |
| 断点中断 | `x/N`，点一下续传 |
| 已下载 | 对勾 + `已下载` |

阅读按钮在有进度时显示 `从第 N 页阅读`（与 E 站详情页同一个字符串 `read_from`），点它直接从那一页开始。

下载行为跟 E 站一致：点一下就进队列，在「下载」页看进度/删除，支持断点续传和离线阅读。

## 2026-11-21 第六轮：阅读进度改成一翻页就落盘

### 问题

从阅读器返回详情页，有时候「看到第几页」没了，按钮又变回 `开始阅读`。两个原因：

1. 进度只在**退出阅读器那一刻**（`onDispose`）写一次。翻页后按 Home 让应用进后台、
   被系统回收、或者返回过程中进程被清掉，这次写入就永远没发生。
2. 写进度要求历史里先有条目，而条目是**打开详情页**时才记的。
   从「下载」页或历史页直接进阅读器时，详情页可能压根没被打开过，`saveProgress` 找不到条目就静默丢弃。

### 改法

- `PageLoader` 新增 `autoSaveProgress`（默认 false，E 站行为不变）；`NhentaiPageLoader` 覆盖成 true
- `ReaderScreen` 的翻页回调里：`autoSaveProgress` 的 loader 每翻一页就 `saveProgress()`
- 退出阅读器时仍然再存一次（双重保险）
- `NhentaiHistory.saveProgress(detail, page)`：条目不存在就**补一条**（标题、页数、缩略图都从详情里拿），
  存在则只更新页码、不动历史顺序
- 进度写入会打日志：`NhentaiHistory: progress 698803 -> page 7/26`，以后出问题直接看日志
- 详情页按钮文案改成 E 站那套 `从第 N 页阅读`（`R.string.read_from`）

### 验证记录

| 检查项 | 结果 |
|---|---|
| 翻页即存 | 读到第 7 页 → 按 Home → `am force-stop` 杀进程 → 重启重进详情，按钮 `Read page 7` |
| 从该页继续 | 点 `Read page 7`，阅读器直接落在 7/26 |
| 退出即存 | 读到第 4 页 → 返回，详情立刻变 `Read page 4`（无延迟、无需刷新） |
| 正常返回 | 读到第 7 页返回，详情保持 `Read page 7` |
| 下载页直接进阅读器 | 先清空历史 → 侧栏「下载」进已下载本子读两页 → 返回，`history.json` 自动出现该条目 `"lastPage":2` |
| 历史页展示 | 历史行显示 `3/26 · 时间` |
| 重启保持 | 强杀进程重开，历史与进度都在（`history.json` 已落盘） |




