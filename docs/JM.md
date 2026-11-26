# 禁漫（18comic / JM）接入说明

第三个内容源。侧栏顶部胶囊从 **E / N** 变成 **E / N / 禁**，导航项跟着切换。

## 为什么走移动端接口，而不是网页

网页站（`18comic.vip`）在我们这边是被 Cloudflare 的「Just a moment...」托管挑战挡住的，
`curl` 直接拿 `/albums` 会拿到 403 + 挑战页；就算拿到 HTML 也要解析一堆结构。

移动端接口不是 Cloudflare 挑战那一层，直接回 JSON：

- 接口域名（依次回退）：`www.cdnhjk.net`、`www.cdngwc.cc`、`www.cdngwc.net`、`www.cdngwc.club`
- 请求头：`token` = md5(时间戳 + `185Hcomic3PAPP7R`)，`tokenparam` = `时间戳,2.1.13`
- 返回：`{"code":200,"data":"<base64>"}`，`data` 用 AES-256-ECB 解密，key = md5(时间戳 + secret) 的字符串
- 用 OkHttp 直连，不走 app 全局的 ktor/Cronet：代理下会报
  `Content-Length mismatch: expected ... but received ...`（代理解压了 gzip 但保留长度）

接口一览（都在 [JmApi.kt](<D:\dev\EhViewer\app\src\main\kotlin\com\hippo\ehviewer\jm\JmApi.kt>)）：

| 用途 | 路径 | 参数 |
|---|---|---|
| 首页 / 分类 | `/categories/filter` | `page`、`c`（分类）、`order=`、`o`（排序） |
| 搜索 | `/search` | `search_query`、`page`、`o` |
| 本子详情 | `/album` | `id` |
| 章节页表 | `/chapter` | `id` |
| 打乱参数 | `/chapter_view_template` | `id`（返回 HTML，正则取 `var scramble_id = N;`） |
| 封面 | `https://<图床>/media/albums/<本子id>.jpg` | — |
| 页面图 | `https://<图床>/media/photos/<章节id>/00001.webp` | — |

分类（接口参数）：`doujin` 同人、`single` 單本、`short` 短篇、`hanman` 韓漫、`another` 其他、`3D`。
排序：`mr` 最新、`mv` 熱門、`mv_w` 週榜、`mv_m` 月榜。

## 图片打乱（scramble）

禁漫的页面图是上下分段打乱的，段数按官方 `get_num` 算：

```
aid < scramble_id            -> 0（不打乱）
aid < 268850                -> 10 段
否则 x = (aid < 421926 ? 10 : 8)，num = (ord(md5(aid + 去后缀文件名)[-1]) % x) * 2 + 2
```

**坑一：末位取的是字符的 ASCII 码（'f' = 102），不是把它当十六进制数字（15）。**
第一版按十六进制算（`digitToInt(16)`），段数整体偏大：守護你第 1 话第 1/2/3 页应为 14/4/14 段，实际算成 16/4/16，
条漫被多切两段倒序，屏幕上就是「横条 + 大片空白」的乱版（2026-11-23 修）。

**坑二：这里的 aid 是章节 id（photo_id），不是专辑 id。**
官方 `JmImageDetail` 构造时写的是 `aid=photo_id`（见 jmcomic `jm_entity.py`），图片 URL 也是
`/media/photos/<章节id>/<文件名>`。单话本两者相同（守護你第 1 話 = 1233797），所以看不出问题；
多话本一错就整页乱：《特色新視界》专辑 1225495、第 2 話 1225497，
按专辑 id 算第 1 页是 14 段、按章节 id 是 8 段，正确答案是 8（2026-11-23 修）。

重排：把图按 `h/num` 切成段（余数并进第一段），**从下往上倒序摆回去**。
实现见 [JmImages.kt](<D:\dev\EhViewer\app\src\main\kotlin\com\hippo\ehviewer\jm\JmImages.kt>) 的 `descramble()`：
下载 → 解码 → 重排 → 存成正常 JPEG（`cacheDir/jm4/<本子>/<章节>/<页>.jpg`），阅读器再走原来的解码流程。

**坑三：临时原图文件不能全章节共用一个。**
第一版把下载的原始图统一落到 `<章节>/raw.bin`，处理完删掉。但阅读器预热是 12 路并发，
A 页刚写完 `raw.bin`，B 页就覆盖了它 —— A 页读到的其实是 B 页的图，再按自己的段数切，
成品就是错位乱版，而且**时好时坏**（取决于下载时序）。现在每页一个 `raw_<index>.bin`。
并发安全的 `jobs` 表也从 `mutableMapOf` 换成 `ConcurrentHashMap`（2026-11-23 修）。

缓存目录带 v4（`jm4`）：段数算错、临时文件串页时期的缓存全是碎图，换目录让它们整体失效。

段数日志：`JmImages: page 3 descrambled num=14 720x3008`。



## 条漫排版

禁漫的韩漫页图是 `720x3008` 这种竖长条（一话几十屏）。左右翻页模式会把整条压进一屏——
内容小得看不清、上下两条大黑边，所以 **韩漫默认走条漫模式**（按屏幕宽度铺满、上下连续滚动，和官方网页版一致）。

- `PageLoader.defaultWebtoon`：默认 false，`JmPageLoader` 对「标签含 韓漫」或「章节数 > 20」的本子返回 true
- `ReaderScreen` 的阅读模式是「跟随设置」时用它决定排版，用户手动选过的模式仍旧优先
- 日漫单话本（比如 ELEACH6）标签里没有韓漫，保持左右翻页

## 作者 / 标签可点

详情页的作者和标签都可点，点一下带着这个词跳到禁漫列表页搜索：

- 作者：位置在标题下方、靠右，`行内` 排成「作者：STUDIO / J」，**斜杠前后各自独立可点**——
  点 `STUDIO` 搜 STUDIO，点 `J` 搜 J（用 `FlowRow(Arrangement.End)` + 每个名字一个
  `Text` + `clickable { onSearch(name) }`；不能整段一个 `Text` 用 `author.first()`，
  那样点哪个都只搜第一个作者）
- 标签：`album.tags` 每项一个 `AssistChip`，`onClick` 从空实现改成搜索
- 落地：`JmListScreen(query = "作者名")` → `LaunchedEffect(query)` 里 `searchFieldState.edit{replace}` 填搜索框 + `vm.search(query)`
- `JmListScreen` 因此带了默认参数 `query: String = ""`，导航抽屉里的入口改成 `JmListScreenDestination()`

### 单个字符的关键词要加通配符

禁漫 `/search` 对**单个字符**的关键词直接回 `total: 0`（实测 `J`、`S`、`雲` 都是 0，
接口不做单字模糊匹配）。像作者「J」这种一点就白屏，看着像点击没生效。

`JmApi.list()` 里对 `query.length == 1` 自动包成 `*J*` 再发：

```
*J*  -> total 630，第一条就是 代孕媽媽
*雲* -> total 1781
```

搜索框里显示的仍是用户输入的原词（`J`），只有请求参数被放宽。

## 详情页排版（参考 E 站 / 网页版重排）

```
[封面]  标题
        作者：A / B          ← 靠右，斜杠前后各自可点
        ♥ 18.1K 喜歡
        👁 151.1K 觀看
        JM1233797
        2781 頁 · 57 話
[下載] [收藏] [Read page 1]  ← 阅读最右主按钮
描述
標籤（FlowRow 换行）
評論 共 386 條（只放最新一条灰卡片 + 查看全部）
Chapters（多话本）
```

- 数字都从 `/album` 直接拿：`likes` / `total_views` / `comment_total` / `is_favorite`，
  `18115 -> 18.1K`、`151118 -> 151.1K` 由 `formatCount()` 处理（服务端给的原始值是字符串）
- 「下載」= `JmDownloader.downloadChapter()`：把这一话（有进度就用进度那一话，否则第 1 話）
  逐页下载 + 去乱序 + 落进 `cache/jm4`，按钮实时显示 `x/N`，下完变「已下載」
- 「收藏」状态优先用 `album.is_favorite`（登录后服务端会给），再叠加本地 `JmFavorites`
- 评论区照 E 站样式：详情页只放**最新一条**灰色圆角卡片（用户名 + 日期一行、正文在下），
  卡片本身可点；下面是「查看全部 N 條評論」，点开是独立的评论页 `JmCommentsScreen`
  （标题「評論（N）」+ 本子名 + 全部评论卡片，返回键回详情）
- 评论滚动加载：`/forum` 的 `page` 参数有效（某本 1097 条，`apiPage` 一路加到 17 都正常）。
  页面维护 `apiPage`，每批循环拉 2 页凑 20 条，`snapshotFlow` 监听倒数第 3 条触发下一批；
  按 CID 用 `LinkedHashMap` 去重，页不满 10 条或拉空即 `hasMore = false`。
  日志：`JmComments: batch +20 loaded=160 total=1097 apiPage=17 hasMore=true`
- 章节列表挪到最后：122 話的列表放在评论前面会把评论区挤没

## 账号 / 收藏 / 评论

### 登录

- 入口：列表页搜索栏最右侧的箭头图标（未登录）/ 头像图标（已登录，点了进收藏）
- 接口：`POST /login`，表单 `username` + `password`，返回加密 JSON（`uid` / `s` / `level` / `coin` / `album_favorites` …）
- cookie = 响应里的 `Set-Cookie`（`ipb_member_id`、`ipb_pass_hash` 等）+ `AVS=<s>`，拼成一整串存进 `files/jm/auth.json`
- 之后所有接口请求都带 `Cookie` 头；重启 App 自动恢复（`JmAuth.load()`）
- 登录页登录后变成账号页：显示账号、等级、J 币、收藏数，带「登出」

### 收藏

- 列表：`GET /favorite?page=&folder_id=0&o=mr`，返回 `{list, folder_list, total}`，三列网格滚动加载
- 切换：`POST /favorite` 表单 `aid`，返回 `{"type":"add"|"remove"}` —— 是 **toggle** 接口，不是 add/rm 两个
- 详情页按钮：未登录点了跳登录页；登录后点一下就切换，状态存在 `JmFavorites` 内存索引里
- `JmFavorites` 没有「查单本是否收藏」的接口，所以登录后拉收藏列表前 5 页（100 本）建索引，
  超出范围的本子第一次会显示成未收藏

### 评论 / 喜欢

- 评论：`GET /forum?mode=all&page=N&aid=<本子id>`，**支持 page 分页**（每页 10 条），
  评论页按「一次 20 条（拉 2 页）、滑到底再拉 20 条」滚动加载，**不设总条数上限**；
  **移动端接口不支持发评论**（官方库直接抛 NotImplementedError，
  要发只能走网页端），弹窗里也写明了
- 喜欢：`/album` 返回里的 `likes` 字段只读展示（`喜歡 8000`），移动端没有点赞接口
- 评论正文是 HTML，展示前去掉标签、`<br>` 转换行、解 `&#123;` 之类的数字实体

## 章节切换（多话本）

三个入口，都只在多话本（`album.series.size > 1`）出现：

| 入口 | 位置 | 条件 |
|---|---|---|
| 「下一話」竖条 | 屏幕右边缘，半透明 | 条漫：`!lazyListState.canScrollForward`（已滚到最底）；翻页：`pagerState.currentPage == last` |
| 「上一話」竖条 | 屏幕左边缘，半透明 | 条漫：`!lazyListState.canScrollBackward`；翻页：`currentPage == 0` |
| 「章節」按钮 | 阅读器底部栏第 4 个（列表图标） | `chapters` 非空 |

- `PageLoader.chapters / currentChapterIndex / switchChapter()`：禁漫从 `album.series` 按 `sort` 排序填进去
- 竖条做成 52dp 宽、整屏高、`Color.Black.copy(alpha = 0.28f)`，文字竖排；`appbarVisible` 时不显示，避免和菜单抢位置
- 切章走 `nav.popBackStack()` + `navigate(ReaderScreenDestination(Jm(albumId, 新章节, 0)))`，
  栈里不会越堆越深，返回直接回详情页；切之前先 `saveProgress()`
- 底部栏按钮从 4 个变 5 个，`BottomReaderBar(onClickChapters = null)` 时保持原样（E 站 / nhentai / 单话本不显示）



## 页面

| 页面 | 文件 | 说明 |
|---|---|---|
| 列表 | `ui/screen/jm/JmListScreen.kt` | 分类胶囊 + 排序下拉 + 搜索 + 三列网格 + 无限滚动 |
| 详情 | `ui/screen/jm/JmDetailScreen.kt` | 封面 / 标题 / 作者 / 页数 / 标签 / 评论预览 / 章節列表 |
| 评论 | `ui/screen/jm/JmCommentsScreen.kt` | 全部评论页（`JmCommentCard` 也供详情页复用） |
| 阅读 | `jm/JmPageLoader.kt` | 复用 E 站阅读器；每翻一页写进度；支持「先显示预览图」的开关（禁漫没有页面预览图，返回 null） |
| 历史 | `ui/screen/jm/JmHistoryScreen.kt` + `jm/JmHistory.kt` | 独立 JSON（`files/jm/history.json`），带章节和页码，可单条删/清空 |

侧栏：`Sites.JM = 2`，`SiteSwitcher` 三个胶囊，`navItemsOf()` 给禁漫自己的导航项（JM / 历史 / 设置）。

## 验证（Android 14 x86_64 模拟器，走代理）

| 检查项 | 结果 |
|---|---|
| 三站胶囊 | 显示 `E / N / 禁`，点「禁」切到禁漫首页 |
| 列表 | 首屏加载出封面、标题、作者；分类切「韓漫」返回韩漫作品 |
| 详情 | 单话本子显示 `27 pages` + 标签；多话本子显示 `2781 pages / 57 chapters` + 章節列表 |
| 阅读 | 打开第 1 页是完整封面；翻到第 2 页画面连续（文字气泡和分镜都正常），说明去乱序生效 |
| 进度 | 返回详情显示 `Read page 2`，`files/jm/history.json` 里 `lastPage:1` |
| 历史页 | 侧栏「历史」显示条目和 `2/27` 进度 |

### 2026-11-23 第二轮（修条漫乱版）

| 检查项 | 结果 |
|---|---|
| 段数（守護你 第1话） | 日志 `page 1 num=14`、`page 2 num=4`、`page 3 num=14`、`page 5 num=12`，与官方 `get_num` 一致 |
| 页图本体 | 从模拟器 `cache/jm2/1233797/1233797/` 取出 2、3 页，720x3008 整条连续（1997 标题页、劉學英门牌 + 校服），无横条错位 |
| 条漫排版 | 韩漫进阅读器按宽度铺满；上滑 3 次从 `3 / 87` 滚到 `4 / 87`，画面连续，和官方网页版一致 |
| 日漫单话本 | ELEACH6（2115x3038、标签无韓漫）仍是左右翻页、整页适屏，第 2 页画面完整 |
| 进度 | 退出后详情显示 `Continue ch. 1 · page 3` → 再进落在 3/87 |

### 2026-11-23 第三轮（多话本仍乱版）

用户反馈《特色新視界》第 2 話还是横条乱版，查出段数用的是专辑 id（应为章节 id）。

| 检查项 | 结果 |
|---|---|
| 段数（特色新視界 第2話） | 日志 `page 1 num=8`、`page 2 num=12`、`page 4 num=12`、`page 6 num=4`（此前 page 1 算成 14） |
| 第 1 页 | 办公室全景（哈囉）→ 左看 → 「這咖啡是誰買的啊? 好合我的口味」，整条连续，白带是原图自带的画面间隔 |
| 第 2 页 | 「今天辦公室和平常沒什麼兩樣」→「今天出貨和截止時間是幾點?」，与第 1 页衔接自然 |
| 回归（守護你 第3页） | 仍是走廊 → 呃呃 → 劉學英门牌，`num` 与上一轮一致，没被改坏 |
| 单话本 | `<章节id == 专辑id>`，段数不变，回归正常 |

### 2026-11-23 第四轮（单话本偶发乱版 → 并发串页）

用户反馈《おっぱいデリバリー陽毬アフター水着編》第 6 頁（46 页单话本）还是横条乱版，且「偶尔」出现。

| 检查项 | 结果 |
|---|---|
| 段数本身 | 日志 `page 6 descrambled num=14`，PC 端按 14 段还原该页完全正确 → 不是段数问题 |
| 定位 | 从模拟器 `cache/jm3/.../6.jpg` 取出成品，本身就是乱图，且内容与第 6 页原图对不上 → 输入图错了 |
| 根因 | 所有页共用临时文件 `raw.bin`，预热 12 路并发互相覆盖 |
| 修复后 | `cache/jm4/.../6.jpg` 是完整「可以來幫我塗一下防曬霜嗎」整页；抽查第 3、7、9 页也都各自完整、无串页 |




## 这一版没做的

- 登录 / 收藏（接口 `/login`、`/favorite` 需要账号，先留空）
- 下载管理（禁漫页图走 `cacheDir/jm2`，阅读时自动缓存，但没接下载页）
- 评论区、排行榜分类（榜单元数据接口未接）
