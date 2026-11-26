# 日志功能（很太之家）

用户报错时可以直接把日志文件发出来，不用连 adb、不用 root。

## 入口

设置 → **日志**（`ui/settings/LogScreen.kt`，`Settings.logEnabled` / `logFolderUri` / `logFolderName`）

| 项 | 作用 |
|---|---|
| 记录日志到文件 | 总开关，默认开。关掉后只保留崩溃记录 |
| 日志文件夹 | 打开系统文件夹选择器；选定后同一份日志自动同步到这个文件夹 |
| 当前日志文件 | 显示当天的文件名，点一下立即同步 |
| 查看最近日志 | 弹窗显示最近 300 行（等宽字体），可以一键复制 |
| 导出日志包 | 把应用内日志、崩溃记录、设备信息打成一个 zip 存到用户选的位置 |
| 清空日志 | 删掉应用内的日志（已同步到文件夹里的不动） |

## 文件长什么样

- 文件名：`很太之家-yyyyMMdd.log`（每天一个），崩溃单独一份 `crash-yyyyMMdd-HHmmss.log`
- 文件开头固定一段头部：版本号、commit、Android 版本、厂商型号、ABI、启动时间
- 正文每行：`11-20 22:16:05.445 I/NhentaiDetail: detail 698740 loaded pages=164 tags=5`
- 崩溃文件结构：`Crash` 头部 → `PackageInfo` → `DeviceInfo`（内存、ABI、指纹…）→ `CrashInfo`（完整栈）→ `RecentLog`（崩溃前 200 行）

## 落盘策略（`util/AppLog.kt`）

1. **应用内**：`filesDir/logs/`，始终写，崩了也在；只保留最近 7 天
2. **用户目录**：SAF 目录树（`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`），
   写的是同一个文件名；写入串行化，避免选目录时补写和定时刷新各建一个文件（会变成 `名字 (1).log`）
3. 主线程不碰磁盘：所有写文件都丢给单线程 `app-log` 执行器
4. 崩溃路径是同步写的：先把还在缓冲里的行刷出去，再写 `crash-*.log`（应用内 + 用户目录各一份）

## 接日志的约定

新代码出问题的地方统一用：

```kotlin
AppLog.i("Tag", "正常事件")
AppLog.w("Tag", "可恢复的问题", throwable)
AppLog.e("Tag", "失败的操作", throwable)
```

异常一定要把 throwable 传进去，报告里最值钱的就是栈。

## 已验证

- 选 `Download/hentailogs` 后文件出现，内容含头部和日志行，只有一个文件（没有 `(1)` 副本）
- `adb shell am crash moe.tarsin.ehviewer.debug` → 目录里出现 `crash-20261120-223322.log`，栈和设备信息齐全
- 应用内日志用 `run-as moe.tarsin.ehviewer.debug cat files/logs/…` 也可读
