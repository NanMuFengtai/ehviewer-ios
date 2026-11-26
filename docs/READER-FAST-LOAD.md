# 阅读器原图加载优化（P0 + P1 + 极速模式）

本轮解决三件事：偶发加载失败、慢节点、以及「第 1 页还没出来，第 2、3 页先好」的割裂感。

回滚点：git tag `reader-load-v1-before-20261124`，改动前的 5 个文件在 `out/backup-20261124/`。

```powershell
# 整体回滚到改动前
git reset --hard reader-load-v1-before-20261124

# 或者只回滚阅读器加载这一块
git checkout reader-load-v1-before-20261124 -- `
  app/src/main/kotlin/com/hippo/ehviewer/spider/SpiderQueen.kt `
  app/src/main/kotlin/com/hippo/ehviewer/spider/SpiderDen.kt `
  app/src/main/kotlin/com/hippo/ehviewer/spider/SpeedTracker.kt `
  app/src/main/kotlin/com/hippo/ehviewer/spider/PageRequestCoordination.kt `
  app/src/main/kotlin/com/hippo/ehviewer/gallery/PageLoader.kt
```

极速模式只是阅读设置里的一个开关，出问题在应用里关掉即可，不用回滚代码。

## 为什么会偶发失败

1. 重试时 `showkey` 没换，服务器一直把请求发到同一台 H@H 节点，节点卡住时三次重试全卡在同一处。
2. 首包超时 10 秒、下载速度低于阈值就判超时，高峰期容易误杀能用的节点。
3. 失败即报错，没有后台重试，用户看到红字但其实预览图是好的。
4. 重试从头下，前面下的几十 KB 白扔。
5. 预取页和当前页抢同一个并发池，翻页时当前页被预取拖慢。

## 改了什么

### P0 稳定性

- **失败换节点**（`SpiderQueen.doInJob`）：每次失败后 `showKey.publish(showKey.beginFetch(), null)` 作废当前 showkey，下一轮重新取页面，服务器会分一个新的 H@H 节点。
- **静默重试**：一页最多试 4 次（`PageLoadPolicy.MAX_ATTEMPTS`），退避 1.2s / 3s / 5s。重试期间不通知 UI，预览图一直挂着；只有 4 次全挂才显示错误页。
- **错误分类**（`PageLoadPolicy.isRetryable`）：超时、连接重置、低俗、hash 不匹配、5xx 自动重试；509 配额、FatalException、4xx 直接报错不白等。
- **当前页优先**：预取队列一次只放行「并发数 - 1」个，永远给当前页留一个位置。

### P1 稳定性 + 速度

- **断点续传**（`SpiderDen.makeHttpCallAndSaveImage`）：失败时保留 `.tmp` 分片，重试带 `Range: bytes=<已下字节>-` 续传；服务器不支持 Range（回 200 而不是 206）时自动从零重下，不会写坏文件。
- **续传后的完整性校验**：整份文件下完后算一次 sha1，和 URL 里的 hash 比对，不一致删分片重下。
- **原图兜底**：H@H 最后一次重试还失败时，改用原图源（不同域名，绕开坏节点）。
- **自适应超时**：首包 12 秒；速度阈值要连续 3 次采样都低于阈值才判超时；已经下了 80% 以上就不再掐。

### 极速模式（阅读设置 → General → Fast mode，默认关）

当前页主请求发出 1.5 秒还没完成，就并行再要一份：优先用原图源（不同域名），没有原图源就向服务器要一个新 H@H 节点。谁先下完用谁，输的那条取消并清掉自己的分片。

只对**当前正在看的那一页**生效，预取页不双发，所以不会把请求数整体翻倍。代价是慢节点上会多一次请求、可能多花一点流量。

### 加载顺序（割裂感）

预取不再一次性全扔进并发池，改成按页序放行：前一张完成才启动下一张，离当前页最近的永远先开始。实测日志：

```
Start download image 0 → 1 → 2
0 succeed → Start 3
1 succeed → Start 4
2 succeed → Start 5
```

## 实测（模拟器 nhtest，478 页本子）

```
# 换节点重试：卡住的节点自动换掉，用户无感
Download image 1 failed (1/4): HttpRequestTimeoutException Timeout
Start download image 1
Download image 1 succeed

# 预取按序推进
Start download image 9 → 10 → 11 → 12

# 断点续传
resume page 5 from 5000 bytes
page 5 range not honoured, restart from zero
Download image 5 succeed

# 极速模式：备援超车主请求
Start racing image 2
Race image 2 backup started
Image 2 won by hath        # 主请求赢
Image 1 won by backup      # 备援赢，接管
```

## 已知限制

- E 站不少 H@H 节点不支持 HTTP Range，这时续传退化成整包重下（日志 `range not honoured`），不会出错，只是没省下流量。
- 极速模式在有原图源的账号上效果最好；游客账号没有原图源时会走「新节点抢跑」，请求数会多一份。
- 取消下载时保留的是主分片；极速模式备援的分片取消即删。
