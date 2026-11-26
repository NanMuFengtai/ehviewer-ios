# 阅读时先显示预览图（E 站 + nhentai）

## 需求

阅读器翻页时，原图要等网络，慢的时候只能盯着转圈。改成：**先把这一页的低清预览图贴上去**，
原图在后台继续下载，下载完成后自动换成原图。功能可以关掉。

## 开关

两处入口，改的是同一个设置项 `reader_preview_first`（默认开）：

- 设置 → 高级 → 先显示预览图（带说明）
- 阅读器内 → 底部设置面板 → 常规 → 先显示预览图

## 实现

### 通用层（两个站点共用）

`gallery/PageLoader.kt`

- 新增 `protected open suspend fun loadPreview(index): Image?`，默认返回 null（下载/归档阅读器不受影响）
- `request(index)` 命中缓存就直接用；否则先 `notifyPageWait` → `requestPreview(index)` → 再走原来的 `onRequest(index)`
- `requestPreview`：开关关着、已有图、或者原图已经就绪时直接跳过；拿到预览图后 `notifyPageSucceed`（进图片缓存、状态变 `Ready`）
- 预取范围的页也顺手拉预览（最多 2 页），翻过去时立刻有东西看
- 原图到达时照旧 `notifyPageSucceed`，缓存里那张预览图被替换 → `PagerItem` 的 `LaunchedEffect(image)` 重启，位图换新，旧的按引用计数回收
- `notifyPageWait(index, expected)` 增加「当前显示的仍是这张图才退回等待」的判断：预览图被回收时不能把刚替换上去的原图打回加载圈
- 日志：`PageLoader: page N preview shown (w x h)` / `PageLoader: page N swapped 200x283 -> 1280x1810`

`image/Image.kt`

- 新增 `Image.ofBitmap(bitmap)`：预览图是直接裁好的位图，不经过解码流程；引用计数从 1 开始，和 `decode` 一样由缓存持有
- `src` 改成可空（位图来源没有 ImageSource）

### nhentai

- 详情里每页自带 `thumbnail`（400×565 左右），比原图小一个数量级
- `NhentaiImages.ensureThumb()` 下到 `cacheDir/nhentai/<id>/preview-<页码>.<ext>`，`Image.decode(PathSource)` 解码后交给 PageLoader
- 已下载（离线可读）或阅读缓存里已经有原图的页直接返回 null，不浪费一次请求

### E 站

E 站的页面预览是 sprite：一张图里横排着好几页的缩略图，`GalleryPreview` 给出 `offsetX` / `clipWidth` / `clipHeight`。
`SpiderQueen` 本来就要取预览列表换 pToken，顺手把 `GalleryPreview` 按页码缓存下来（`previewCache`）。

`image/PreviewImageLoader.kt`：

- 需要时再按 `p` 参数翻预览页，`EhEngine.getPreviewList()` 补齐
- sprite **自己下载、自己解码**，不走 Coil：Coil 链上的 `HardwareBitmapInterceptor` 会把结果换成 hardware bitmap
  并 `recycle()` 原图，拿到的一般不是 `BitmapImage`，复用它很容易踩「Can't copy a recycled bitmap」
- 缓存的是 sprite 的**字节**（32 MB LRU），每次按页解码再裁一刀，每页一张独立的位图，
  交出去的 `Image` 回收时只回收自己那张，互不影响

## 预热（第六轮补的）

一开始只垫当前页 + 1～2 页预取，翻远一点还是转圈。现在：

- 进阅读器就起一个预热协程：**一次 30 页**同时拉，拉完接着下一批 30 页，最多铺到 600 页，退出阅读器就停
- 并发上限 12（`previewSemaphore`），预览图小，快一点更跟手
- 当前页仍然优先进 `request()`，不会被预热的海量请求挤在后面
- E 站的 sprite 加了**磁盘缓存**（`cache/eh_preview/*.bin`），同一张 sprite 被几十页共用，
  第二次进同一本直接读盘；同一 URL 的并发请求用 Mutex 串行化，避免 30 页同时下同两张图
- E 站已经下载好的页（`SpiderQueen.isPageFinished`）跳过预览，离线看书不产生无谓请求

实测（模拟器 + 代理，比真机直连慢很多）：

| 站点 | 进度 |
|---|---|
| E 站 | 进阅读器 8 秒内铺到 60 页预览；连翻 10 页到第 11 页，0.6 秒就有画面 |
| nhentai | 详情加载完后 2～3 秒铺满 30 页；6 秒时 30 页预览就位 |

## 原图失败时仍然给重试按钮

预览图铺开之后暴露了两个问题（都修了）：

1. `request()` 命中缓存就直接 `Ready`，而缓存里可能只是一张预览图 —— 于是**原图的请求根本没发**，
   页面永远停在低清图，也就永远不会有失败、不会出重试按钮。
   现在 `Image` 带 `isPreview` 标记：命中预览图时照样显示预览，但会补发一次原图请求。
2. 原图失败时 `notifyPageFailed` 只把状态改成 `Error`，缓存里的预览图还在 ——
   下次 `request()` 又把它当成已加载贴回来，把重试按钮盖掉。
   现在失败时会先把这页的清掉缓存再置 `Error`；`requestPreview` 也不再覆盖 `Error` 状态。
3. 预取判断从「没有图」改成「还差原图」（`Page.needsOriginal`），只有预览图的页也算没取完。

实测（nhentai，129 页本子，开飞行模式）：

- 日志：`PageLoader: page 5 failed: ... net::ERR_PROXY_CONNECTION_FAILED`
- 翻回第 5 页：显示错误信息 + **Retry 按钮**（之前是空白预览、没有任何重试入口）

## 验证（Android 14 x86_64 模拟器，走代理）

| 站点 | 检查项 | 结果 |
|---|---|---|
| nhentai | 打开新本子 | 1 秒内 `page 1..4 preview shown (400x565)`，原图到达后 `page 1 swapped 400x300 -> 1280x961` 等 |
| nhentai | 截图对比 | 预览帧（约 1.0 MB PNG）→ 原图帧（约 1.24 MB PNG），画面清晰度明显变化 |
| E 站 | 打开新画廊 | `page 9..16 preview shown (200x283)`，原图到达后 `page 7 swapped 200x283 -> 1280x1810` |
| E 站 | 快速连翻到第 10 页 | 当前页也有预览（`page 10 preview shown`），不再空白转圈 |
| E 站 | 已下载/已缓存页 | 直接出原图，不触发预览请求 |
| 关闭开关 | `reader_preview_first = false` | `requestPreview` 直接 return，行为与改动前一致 |

## 已知限制

- E 站预览图分辨率取决于官方 sprite（约 200×283），放大会有点糊，这是「先看个大概」的代价；原图到达后自动变清晰
- 预览图也占用图片缓存，快速连翻很多页时会有几 MB 的低清图在缓存里，翻过去会被正常淘汰
- 断网时 E 站预览取不到（显式失败日志），nhentai 预览也取不到，退化成原来的转圈行为

## 「Cancelled」和黑屏（第七轮修）

用户截图：读到一半整页黑底，中间 `Cancelled` + 重试，页码还在走。三个原因叠在一起：

1. **取消被当成失败**：页面滑出屏幕时 `PagerItem` 的 `onDispose` 会 `cancelRequest()`，
   `SpiderQueen` 里任务被取消后上报 `notifyPageFailure(index, "Cancelled")`，
   状态就永远停在 `Error`，翻回来还挂着重试按钮。
2. **失败时清掉了预览**：`notifyPageFailed()` 为了「让重试按钮能出来」把缓存里的预览图删了，
   于是错误页没有任何底图，只能黑屏。
3. **错误页占位只有一块**：`aspectRatio(1/1.4125)` 的框 + 缩略图 `ContentScale.Inside`，
   低清图按原始像素画在中间，看着像一小张贴纸。

改法：

- `PageLoader.notifyPageCancelled()`：请求被取消时不动 `Error`，有预览就退回
  `Ready(preview)`，没有就回 `Queued`（下次显示时自动重发原图）。日志
  `page N request cancelled, preview=true`
- `notifyPageFailed()` 里先判断 `error == "Cancelled"`（`CANCELLED_MESSAGE`）走取消分支
- 真失败时把预览图**钉住**存进 `errorPreviews`（最多 24 页，LRU 释放），
  `cache` 里照旧清掉，避免 `request()` 把 `Error` 又刷回 `Ready`
- `PagerItem` 的 `Error` 分支：`PreviewBackdrop` 用 `ContentScale.Crop` 铺满整屏 + 45% 黑遮罩，
  上面是「无法加载该图片」+ 两行小字错误 + `Retry`
- 新增 `fillPageHeight`：翻页模式错误/等待页铺满整屏（上下不再留黑边），
  条漫模式仍按页面比例占一块，避免 LazyColumn 里无限高度
- 预热前先单独把**当前页**的预览请求发出去并等它完成，再批量铺后面的页

## 预览 → 原图的无感替换（第八轮修）

用户反馈：原图加载完替换预览图时，「中间又加载一遍」。两个原因：

1. **预览图按原始像素画**：E 站预览只有 200×285，`ContentScale.Inside` 不会放大，
   于是页面中间出现一张小贴纸，原图到了才「啪」地铺满 —— 看着就是重新加载。
   现在 `image.isPreview` 时统一用 `ContentScale.FillWidth`，预览和原图占位一致。
2. **替换瞬间旧图被提前回收**：`LaunchedEffect(image)` 重启时，旧 effect 的 `finally`
   立刻 `unpin()` 旧图，painter 还指着它，下一帧可能画到已回收的位图（闪黑/空白）。
   现在把「当前显示的图 + painter」一起存进 `displayed`，先把新 painter 挂上去，
   `withFrameNanos {}` 等新图至少画过一帧再 `unpin()` 旧图。
   日志：`PagerItem: page 1 swap 200x285 -> 1280x1823 seamless`
   只有这页**第一次**合成时才会出现 `blank frame (no painter)`，swap 过程中不出现。

另外，进阅读器时当前页的预览不再排预热队列（`requestPreview` 里
`index == prevIndex` 时不走 `previewSemaphore`），首屏更快出图。

## 替换时又转圈的真正原因（第九轮）

上一轮修了「预览图不铺满」和「旧图提前回收」，用户升级后仍然闪加载画面。抓状态日志看到：

```
page 1 state=Queued   → 进阅读器
page 1 state=Loading  → 开始下原图
page 1 state=Ready    → 预览图铺上
page 1 state=Loading  → 原图下载进度上报，把 Ready 打回加载圈（黑屏转圈）
page 1 swapped ...    → 原图到
```

**根因**：`EhPageLoader` 在下载原图时每收到一段数据就 `notifyPagePercent(index, 进度)`，
而 `notifyPagePercent` 无条件把状态换成 `PageStatus.Loading`。预览图已经显示了，
一有下载进度就又变回转圈，直到原图解码完成。

**改法**：`notifyPagePercent` 遇到 `PageStatus.Ready` 时原样保留（进度只在后台），
只有 `Queued / Loading` 才切到 `Loading`。修复后日志：

```
Queued → Loading（首次没图）→ Ready（预览）→ swap seamless（原图）
```

替换过程不再出现 `Loading`。`PagerItem` 里加了一行状态日志便于以后排查：
`PagerItem: page N state=...`
