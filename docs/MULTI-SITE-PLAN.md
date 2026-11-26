# 多站点改造方案（E 站 + 自选站点）

目标：把当前"只为 E 站写死"的 EhViewer 改成可以切换内容源的客户端。第一个新增站点是 nhentai.net。
本文所有站点事实均已实测（2026-11-20，走本机 127.0.0.1:7890 代理）。

## 0. 先说结论

能加，而且 nhentai 这一侧比 E 站**简单一个数量级**。

真正的难点不在"从新站点取图"，而在"把 E 站绑死的东西摘出来"。现在工程里有 4 处硬编码把站点钉死了：

1. `EhUrl` 用全局 `Settings.gallerySite` 拼所有 URL（单例，只能有一个站）
2. Room 的 `GalleryEntity` 主键只有 `GID`，没有任何站点字段 —— 两个站的数字 id 会撞
3. `MainActivity.navItems` 是写死的 8 项，全部指向 E 站的目的地
4. `SpiderQueen` / `SpiderDen` 的缓存 key、下载目录都用 `gid` 命名

不改这 4 处，加多少个站点都是互相踩。

## 1. nhentai 实测事实（已连通验证）

### 1.1 接口

旧接口 `/api/gallery/{id}` 已废弃，返回：

```
HTTP 403
Use new API https://nhentai.net/api/v2/docs
```

新接口是 FastAPI 风格的 REST，OpenAPI 规范在 `https://nhentai.net/api/v2/openapi.json`（244 KB，约 140 个路径）。

和客户端相关的只有这几个：

| 用途 | 请求 | 说明 |
|---|---|---|
| 首页/最新 | `GET /api/v2/galleries?page=1&per_page=25` | 分页对象 |
| 搜索 | `GET /api/v2/search?query=&sort=date&page=1` | sort: date / popular / popular-today / popular-week / popular-month |
| 按标签 | `GET /api/v2/galleries/tagged?tag_id=35762&sort=date&page=1` | |
| 详情 | `GET /api/v2/galleries/{id}` | 含全部页表 |
| 热门 | `GET /api/v2/galleries/popular` | 返回数组，无分页 |
| 随机 | `GET /api/v2/galleries/random` | |
| 标签列表 | `GET /api/v2/tags/{tag_type}?per_page=` | tag_type: tag / category / language / artist / character / group / parody |
| 标签详情 | `GET /api/v2/tags/{tag_type}/{slug}` | |
| CDN 列表 | `GET /api/v2/cdn` | 图床域名 |
| 登录 | `POST /api/v2/auth/login` | 返回 token，收藏需要 |
| 收藏 | `GET/POST/DELETE /api/v2/favorites`、`/galleries/{id}/favorite` | |

分页结构统一：

```json
{ "result": [ ... ], "num_pages": 330977, "per_page": 25, "total": 661905 }
```

列表项：

```json
{
  "id": 698736, "media_id": "4342109",
  "english_title": "...", "japanese_title": "...",
  "thumbnail": "galleries/4342109/thumb.webp",
  "thumbnail_width": 250, "thumbnail_height": 354,
  "num_pages": 7, "num_favorites": 0,
  "tag_ids": [73750, ...], "blacklisted": false
}
```

详情（关键：**一次就拿到全部页表和地址**）：

```json
{
  "id": 698736, "media_id": "4342109",
  "title": { "english": "...", "japanese": "...", "pretty": "..." },
  "cover":    { "path": "galleries/4342109/cover.webp.webp", "width": 1280, "height": 1810 },
  "thumbnail":{ "path": "...", "width": 250, "height": 354 },
  "tags": [ { "id": 1, "type": "tag", "name": "big breasts", "slug": "big-breasts", "count": 210694 } ],
  "num_pages": 7,
  "pages": [
    { "number": 1, "path": "galleries/4342109/1.webp", "width": 1280, "height": 1810,
      "thumbnail": "galleries/4342109/1t.webp", "thumbnail_width": 200, "thumbnail_height": 283 }
  ]
}
```

### 1.2 图片怎么取

```
GET https://nhentai.net/api/v2/cdn
→ { "image_servers": ["https://i1.nhentai.net", ..., "https://i4.nhentai.net"],
    "thumb_servers": ["https://t1.nhentai.net", ..., "https://t4.nhentai.net"] }
```

图片地址 = `image_servers[n]` + `/` + `pages[i].path`，缩略图 = `thumb_servers[n]` + `/` + `pages[i].thumbnail`。

实测（无 Referer、只带 UA）：

```
GET https://i1.nhentai.net/galleries/4342109/1.webp
→ 206 Partial Content, image/webp, 支持 Range

GET https://t1.nhentai.net/galleries/4342109/1t.webp
→ 200, image/webp, 14 KB
```

也就是说：

- **不需要 Referer**（E 站必须带）
- **不需要逐页解析 HTML**（E 站要 `/s/{pToken}/{gid}-{n}` 一页一页抠）
- **没有 pToken / H@H / 509 / showkey / GP**
- **支持 Range**，多线程分块下载可以直接实现

### 1.3 已知坑

| 坑 | 现象 | 处理 |
|---|---|---|
| 不带 UA 直接 403 | `curl` 裸请求返回 `403` 纯文本 | 必须带浏览器 UA（现有客户端已统一注入 Chrome UA，天然满足） |
| 有 Cloudflare | 响应头 `Server: cloudflare`，含 `cf-nel` | 国内需要代理；本机走 7890 代理可通，直连 403 |
| 缩略图双扩展名 | `pages[i].thumbnail` 偶发 `1t.webp.webp`、`thumb.webp.webp` | 实测双扩展名 URL 也返回 200（服务端有别名），但**建议规范化**成单扩展名，避免将来失效 |
| 旧接口误解 | 照老教程调 `/api/gallery/{id}` 必失败 | 只用 `/api/v2/*` |
| 登录态 | 未登录时收藏、我的标签不可用 | 一期先不做登录 |

## 2. 目标架构

### 2.1 一个接口管住所有站点

新增 `source/` 包，定义：

```kotlin
interface SiteSource {
    val key: String                       // "eh" / "nhentai"
    val displayName: String
    val capabilities: List<NavEntry>      // 侧栏由它决定

    suspend fun list(query: ListQuery): PagedGalleries   // 首页/搜索/标签/热门
    suspend fun detail(gid: Long): SourceGalleryDetail   // 含页表
    fun imageTargets(gid: Long, detail: SourceGalleryDetail): List<ImageTarget>  // 可直接给全部 URL
    suspend fun fetchImage(target: ImageTarget, sink: ...)  // 或复用通用下载器
    suspend fun signIn(...)               // 可选，默认 notSupported
}
```

`ImageTarget` 里带 `url`、`referer?`、`headers`、`expectedSha1?`、`extension`。

E 站适配器 `EhSource`：**不改 E 站内部逻辑**，只做门面——`list` 走 `EhEngine.getGalleryList`，`detail` 走 `getGalleryDetail`，取图仍由 `SpiderQueen` 负责（它内部逐页解析）。为了统一，给 `ImageTarget` 一个 `lazy` 变体：E 站返回"需要现算"的目标，nhentai 返回"直接可下"的 URL。

nhentai 适配器 `NhentaiSource`：纯 JSON，一次 detail 就把 `pages[]` 全拿到，取图模块退化成"按 URL 下载"。

### 2.2 侧栏跟着站点变

现在（`MainActivity.kt:173-182`）是写死数组：

```kotlin
private val navItems = arrayOf(
    HomePage, Subscription, Whatshot, Toplist, Favourites, History, Downloads, Settings
)
```

改成：

```kotlin
val navItems = currentSource.capabilities.map { it.destination(sourceKey = currentSource.key) }
```

导航目的地参数化：`GalleryListScreen(sourceKey: String, mode: ListMode)`，不再为每个 E 站入口写一个目的地。

两站的侧栏对比：

| 位置 | E 站 | nhentai |
|---|---|---|
| 1 | 主页 | 首页（最新） |
| 2 | 订阅 | 热门 |
| 3 | 热门 | 标签 |
| 4 | 排行 | 随机 |
| 5 | 收藏 | 收藏（需登录，未登录不显示） |
| 6 | 历史 | 历史（本地） |
| 7 | 下载 | 下载 |
| 8 | 设置 | 设置 |

**不做的入口直接不显示**，不做点了报错的空壳。

### 2.3 数据层必须动（这是主要工作量）

现状：`GalleryEntity` 主键只有 `GID`（Long），没有站点列。nhentai 的 id 也是纯数字，必然和 E 站撞 id。

改法：

1. `GalleryEntity` 加 `@ColumnInfo(name = "SITE") val site: String`，主键改成复合 `(SITE, GID)`；
2. Room 迁移 23 → 24：新建表 → `INSERT INTO new SELECT 'eh', * FROM old` → 换名（现有数据全部归属 E 站）；
3. 缓存 key `getImageKey(gid, index)` → `getImageKey(site, gid, index)`；
4. 下载目录命名从 `<画廊标题>` 改到 `<site>/<画廊标题>`，或保留原样但 `DownloadInfo` 加 site 列（推荐后者，少动文件系统）；
5. 历史/收藏/下载查询一律带 site 过滤。

不改这一步，两个站的书会混成一片，且 E 站的历史/收藏会串到 nhentai 上。

### 2.4 站点设置

现在"画廊站点"是 E/EX 二选一（`Settings.gallerySite`）。

改成分层：

- 顶层：内容源 = E 站 / nhentai（新设置项 `content_source`）
- E 站内部仍保留 E/EX 选择（现有 `gallerySite` 不动）

## 3. 分期

### P1 抽象层 + E 站回归（不改行为）

- 建 `source/` 包，`SiteSource` 接口 + `EhSource` 实现（门面包现有代码）
- `GalleryEntity` 加 site 列 + 迁移 + 缓存 key 带 site
- 侧栏改成从 source 取
- 验收：编出 APK，E 站首页/订阅/热门/排行/收藏/历史/下载/阅读全部照旧

### P2 nhentai 只读链路

- `NhentaiSource`：列表（最新/热门/搜索/标签）、详情、页表
- 取图：JSON 拿 URL → 通用下载器（带 Range、断点、超时重试）
- 阅读器接入：`PageLoader` 的 `openSource` 换成按 URL 下载到缓存再解码
- 验收：nhentai 的列表、详情、阅读、单本下载全部可用

### P3 开关与数据隔离打磨

- 设置里切换内容源 + 侧栏即时刷新
- 历史/收藏/下载按站点隔离
- nhentai 登录 + 收藏（可选）
- 验收：两站来回切，数据不串

## 4. 风险与前置条件

1. **网络**：nhentai 有 Cloudflare，本机直连 403、走代理 200。真机在国内直连大概率失败。建议把"代理设置"一起加（现在工程里 `grep -i proxy` 零命中）。
2. **API 刚换代**：v2 是 2026 年新上的，v1 已废弃。字段可能继续变，JSON 模型要允许未知字段、缺字段降级。
3. **id 冲突**：不在 P1 把 site 打进主键，后面返工很痛。
4. **真机验证**：当前 `adb devices` 为空。要么插手机开 USB 调试，要么我要装模拟器（约 2 GB）。
5. **翻译**：nhentai 的标签、分类是自己一套 taxonomy，E 站的标签翻译库不适用，P2 先原样显示。

## 5. 下一步

按 P1 开工：先把抽象层和数据层改完、E 站行为零变化地编出 APK；P1 过了再上 nhentai。
