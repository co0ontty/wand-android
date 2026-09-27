# Android 客户端逐函数审计与整改建议

审计日期：2026-09-27
审计对象：`android/`（git `1ec5d90`），161 个 `.kt` + 12 个 `.java`，主源码 31,508 行 / 全模块（含测试）50,445 行。
方法：逐文件通读 `app/src/main`（data / app / ui / speech 四个包全部读完，`androidTest`/`test` 作为反向引用证据），
对所有 `private`/`internal` 函数、顶层 `val` 做全仓引用计数（含测试、文档、XML）确认死代码，
对康普斯动效、轮询、线程、网络取消、凭据存储做定向取证。

结论速览（按严重度）：

| 级别 | 数量 | 代表问题 |
| --- | --- | --- |
| P0 缺陷 | 4 | 附件读取可 OOM、请求无法取消、轮询不随生命周期停、通知声音无法关闭 |
| P1 缺陷 | 6 | 空/畸形 baseUrl 导致无限重连、连接成功却丢弃 cookie、`isStructured` 默认值、seq 退避失效、`!!`/引用比较脆弱点、silent `take(5)` 丢文件 |
| P2 死代码 | 13 处（约 400 行） | **已于 §6b 全部清理** |
| P3 架构/可维护 | 9 项 | 1000 行单 Composable + 50 个状态变量、重复实现、未 remember 的重计算 |
| P4 规范偏差 | 6 项 | 硬编码动画时长、Toast 承担结果、注释与实现不符 |

---

## 0. 必须先修的四类 P0

### P0-1 附件上传：先 `readBytes()` 全量入内存，再判 10MB

- `ui/screens/ComposerHelpers.kt:156-172 readAttachment()`：`openInputStream(uri)?.use { it.readBytes() }`，
  没有任何大小上限。用户在「从文件选择」里点一个 2GB 的镜像/视频，就是一次 OOM（进程被杀，`WandLog` 里只会留下一条崩溃）。
- `ComposerHelpers.kt:128-152 launchAttachmentUpload()` 又做了一次 `uris.take(5)`，`data/WandApi.kt:306-336 uploadAttachments()`
  内部再做第三次 `files.take(5)`，10MB 校验同样发生在字节已经在堆上之后。
- 超过 5 个文件是**静默丢弃**：`OpenMultipleDocuments` 不限数量，UI 只提示「已上传 N 个附件」，用户不知道另外几个没了。

整改：

```kotlin
// ComposerHelpers.kt
private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
private const val MAX_ATTACHMENTS = 5

internal fun readAttachment(context: Context, uri: Uri): Pair<String, ByteArray> {
    val resolver = context.contentResolver
    val name = queryDisplayName(resolver, uri) ?: "attachment"
    val declared = querySize(resolver, uri)          // OpenableColumns.SIZE，可能为 null
    if (declared != null && declared > MAX_ATTACHMENT_BYTES) {
        throw WandApiException(null, "$name 超过 10 MB（${declared / 1024 / 1024} MB）")
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (buffer.size() + n > MAX_ATTACHMENT_BYTES) {
                throw WandApiException(null, "$name 超过 10 MB")
            }
            buffer.write(chunk, 0, n)
        }
        buffer.toByteArray()
    } ?: throw WandApiException(null, "无法读取 $name")
    return name to bytes
}
```

并且把「超过 5 个」变成可见的拒绝：`launchAttachmentUpload` 里
`val accepted = uris.take(MAX_ATTACHMENTS); if (uris.size > accepted.size) onToast("一次最多 ${MAX_ATTACHMENTS} 个附件，已忽略其余 ${uris.size - accepted.size} 个")`，
同时把 `WandApi.uploadAttachments` 的 `files.take(5)` 换成 `require(files.size <= MAX_ATTACHMENTS)`（杜绝第二处静默截断）。

### P0-2 OkHttp 请求无法被协程取消

`WandApi.execute()/executeOrThrow()/executeWithRetry()`（`data/WandApi.kt:62-125`）在 `withContext(Dispatchers.IO)` 里调用
阻塞式 `Call.execute()`。协程取消**不会**中断阻塞线程，也不取消 `Call`：

- 用户点开 QuickCommit 后立刻返回列表 → 那个 180s 的 `/quick-commit` 仍在跑，占一个 IO 线程，服务端继续提交/推送；
- `createMission`（180s）、`uploadAttachments`（60s）、`generateCommitMessage`（180s）同理；
- `ChatStore.shutdown()` 之后，飞行中的 `api.sendInput` 也一样。

整改（两者取一，推荐 A）：

```kotlin
// A. 让 Call 随协程取消
private suspend fun executeOrThrow(request: Request, timeoutSec: Int): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        val call = clientFor(timeoutSec).newCall(request)
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback { /* 用 cont.resume */ })
            }
        }
    }
// B. 最小改动：runInterruptible 包一层（只能中断等待，不取消服务端请求）
withContext(Dispatchers.IO) { runInterruptible { requestClient.newCall(request).execute() } }
```

A 方案顺带解决 P2 里「`execute()` 每次新建 OkHttpClient 派生实例」的浪费（改为 `clientFor(timeoutSec)` 复用）。

### P0-3 后台轮询：三处生命周期不一致

| 位置 | 间隔 | 生命周期 | 结论 |
| --- | --- | --- | --- |
| `ui/screens/TaskListState.kt:83-95 startSync()` | 10s | 无 | **后台继续轮询**（根数据源，App 存活期间不停） |
| `ui/screens/MissionsScreen.kt:139-148` | 4s | 无 | **后台继续轮询** |
| `ui/screens/TaskListScreen.kt:207-213`（nowMillis） | 30s | 无 | 后台继续唤醒 |
| `TaskBoardScreen.kt:248-253`、`TaskBoardTaskScreen.kt:121-126`、`WorkspaceTaskScreen.kt:103-108` | 6s | `repeatOnLifecycle(STARTED)` | 正确，照此改 |

`TaskListState` 是首页真源，还额外订阅 `port.taskChanges`，所以后台一次息屏就白跑一整天。
整改：把轮询交给调用方注入生命周期，`TaskListState` 不再自己开无限循环：

```kotlin
// TaskListState.kt
fun startSync() { if (syncing) return; syncing = true; scope.launch { port.taskChanges.collect { load(silent = true) } } }
suspend fun poll(): Boolean = load(silent = true)   // 由 UI 决定节奏与前台条件
```
```kotlin
// WandApp.kt ReadyContent
val lifecycleOwner = LocalLifecycleOwner.current
LaunchedEffect(taskState, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        taskState.startSync(); taskState.load(silent = taskState.groups.isNotEmpty())
        while (true) { delay(10_000); taskState.poll() }
    }
}
```
`MissionsScreen` 与 `TaskListScreen` 的 nowMillis ticker 同理（ticker 只在 STARTED 内运行）。

### P0-4 通知提示音在设置页无法关闭（死配置 + 用户可见后果）

- `ServerStore.setNotificationSoundEnabled/setNotificationSound/setNotificationVolume`：全仓 **0 个调用点**；
- `NotificationHelper.playNotificationSound()` 每次都读默认值 `"chime"` / 音量 80，`sendNotification()` 成功即播放；
- `SettingsScreen` 的「外观与反馈」卡片只渲染了 `AppearanceModePicker` + `NotificationFeedbackContent`（仅「振动反馈」一个 Switch），
  但分组说明写着「调整这台设备上的主题、**声音**和触感」。

用户没有任何入口静音/换音/调音量，只有关掉系统通知或用系统级静音。整改二选一（需产品决策，本文只给实现建议）：

- 补齐 UI：在 `AppearanceModePicker` 下方加「提示音」ActionRow（4 个预设 + 「关闭」），走 `HomeSettingsActions` 新增
  `isNotificationSoundEnabled/setNotificationSoundEnabled/getNotificationSound/setNotificationSound`；
- 或删除死配置（`NotificationHelper` 改为固定静音，`SOUND_PRESETS`/`playPresetSound`/`isValidSound` 一并删掉，`res/raw/notif_*.ogg` 可删 4 个文件），并把分组文案里的「声音」去掉。

按仓库规则「没人读的配置项 = 死代码」，但这条有用户可见后果，属于「顺手修掉同类问题」范畴，不能只删代码了事。

---

## 1. `data/` 层（6,013 行）

### `WandHttp.kt`（267 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `normalizeBaseUrl(raw)` :250 | 空串/`http://` → `"http:"`（`trimEnd('/')` 把 `http://` 削成 `http:`）。下游 `Request.Builder().url()` 抛 `IllegalArgumentException`（非 `IOException`），绕过 `executeOrThrow` 的转换；在 `WandApp` 的认证循环里它不是 `AuthException`/状态码异常，于是被判定为「可重试」，用户看到「连接失败，正在自动重试…」**永久转圈**而拿不到「地址不合法」 | 解析失败即返回 null：`fun normalizeBaseUrl(raw): String?`，或在 `WandApp.kt:126-145` 把 `IllegalArgumentException` 归类为不可重试并给出「服务器地址无效，请重新连接」 |
| `get(url, timeoutMs, trustOriginBaseUrl = null)` :230 | `trustOriginBaseUrl ?: url` 让**只传 URL 的调用永远走 trust-all client**。当前生产调用点都显式传了 serverUrl（`UpdateManager`、`ConnectActivity`），但签名在鼓励错误用法 | 去掉默认值，强制显式传 origin；或改成 `requireNotNull` |
| `requestClient(...)` :195 | 语义正确（同源 trust-all / 跨源系统校验），但 `publicClient` 与 endpoint client 共享 `ConnectionPool` 之外的 dispatcher 派生关系没注释清 | 补注释；`publicClient` 显式 `connectionPool(ConnectionPool())` 与 endpoint 隔离 |
| `resetClient(baseUrl)` :114 | 在主线程 `shutdownNow()` 派生 client 的 dispatcher（`newBuilder()` 共享 dispatcher），会让**调用方仍在持有的 client 立即失效**；`ConnectActivity.saveActivateAndLaunch` 正是登录成功后立刻调用 → 白丢刚拿到的 session cookie（见 P1-2） | 只在「凭据被替换/清除」时调用；成功登录后不要调 |
| `MemoryCookieJar` | 内存态、进程级、跨 endpoint 隔离，符合设计；但 `__Host-` 前缀 cookie 不校验 Secure/SameSite，且 `saveFromResponse` 对同 name 不判 path 优先序（同 name 不同 path 会互相顶掉） | 按 (name, domain, path) 全键匹配已实现；补一条「同名不同 path 保留更具体 path」的单测 |
| `looksLikeHttpOnTlsPort` / `preferHttpsUrl` | 逻辑正确，注释清楚 | 保持 |

### `WandAuth.kt`（100 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `decodeConnectCode` :29 | `token.length < 16` 魔法值；`url.startsWith("http")` 允许 `httpz://`（`httpz://x#tok…` 也能通过，交给下游 `canonicalBaseUrl` 兜底报错） | 改 `startsWith("http://") || startsWith("https://")`，常量具名 |
| `loginWithToken(baseUrl, appToken, client)` :57 | 只 catch `IOException`；`IllegalArgumentException`（坏 URL）穿透为未处理异常 | catch 后按 `AuthException(retryable=false)` 抛，文案「服务器地址无效」 |

### `WandApi.kt`（841 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `execute` :62 | 每次非 30/180s 超时都 `client.newBuilder()` 新建实例；`System.nanoTime` 计时 OK | 按 (endpoint, timeout) 缓存派生 client |
| `executeWithRetry` :100 | 401 后重登再发**同一个 Request**（body 已缓冲，安全）；但重登失败统一报 401，与真正的 401 无法区分 | 保持；补 `retried` 标记日志 |
| `uploadAttachments` :306 | 见 P0-1（`take(5)` + 先读字节后校验大小） | 见 P0-1 |
| `dispatchBoardTask` :540 | `workspaceId !== UNSET_WORKSPACE`（`data/WandApi.kt:552`）是**引用比较**，只有调用方原样传入哨兵常量才成立；任何 `"UNSET".trim()` 或跨进程反序列化都会让它误判成「要写 workspaceId=null」 | 改 `!= UNSET_WORKSPACE`，或把哨兵换成 `sealed class WorkspaceRef { Unset, Explicit(null), Id(v) }` |
| `encode(value)` :153 | `URLEncoder.encode` 把空格编码成 `+`。用于 query 正确，用于**路径段**错误（`/api/tasks/{id}`）。当前所有路径参数都是 UUID，暂无触发路径 | 拆成 `encodePath`（不转 `+`）/`encodeQuery` 两个函数 |
| `clearWorkspaceTaskSessions` :752 | 先 `workspaceTask(taskId)` 再批量删；两步之间任务被别的客户端清空 → 白跑一次删除请求（返回 0） | 无害，保持 |
| 其余（sessions/queued/models/permissions/missions/quick-commit/board/workspace） | 路径、方法、超时与服务端路由一致；`quickCommit`/`generateCommitMessage`/`createMission` 的 180s 与「AI + push 慢」匹配 | 保持 |

### `WandSocket.kt`（376 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `handleText` :262 | seq 间隙 → resync 逻辑正确（PTY 分支在 `awaitingPtySnapshot` 时只 ack 不渲染）；但 `onEvent` 与 `onPtyEvent` 都无条件转发，订阅方必须自己按 sessionId 过滤（`ChatStore.handle` 做了，`SessionWatcher` 依赖「全局广播」，`NativePtyTerminal.handle` 也做了） | 保持，补一条「两个回调的契约」注释 |
| 退避：`onMessage` 里 `reconnectDelayMs = 1_000L` :218 | 服务端每 20s 发应用层 ping，**每次 ping 都把退避复位**，半开/抖动场景下退避形同失效（最多 2s 一轮重连） | 只在「成功订阅并收到 init」后复位：把 `reconnectDelayMs = 1_000` 从 `onMessage` 挪到 `init` 分支 |
| `openSocket` :190 | 每次调用（含每次重连）新建 `CoroutineScope(Main.immediate)`，job 只在 close/前台重建时取消；无泄漏但对象略多 | 复用一个 `authScope` |
| `sendPtyInput` :145 | 严格实现「先文本、后单独 `"\r"`」，`shortcutKey` 语义与仓库契约一致 | 保持（这是最容易写错的地方，实现是对的） |
| `ptyInputChunks` :336 | 按 code point 切包、不会切碎 UTF-8；转义序列可能跨包，PTY 是字节流所以安全 | 保持 |
| `close()` :132 | 不置 `onPtyEvent/onEvent = null`；store 侧引用随对象丢弃 | 保持 |

### `WandModels.kt`（1220 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `str/bool/int/dbl/obj/arr` :34-44 | 逐字段容错策略正确；`bool()` 用 `opt as? Boolean`，服务端若下发 `0/1` 会变成 null（当前都是真 bool） | 保持 |
| `arrayField` :72 | 处理 `claude -p` 把数组拍成字符串的情况，必要 | 保持 |
| `structuredContentText` / `structuredToolImages` :91-160 | 图片 part 不抽文本、不 JSON 兜底，避免把 base64 倒进正文——正确 | 保持 |
| `ContentBlock.parse` :246 | 未知类型落到 `Unknown(payload)` 并在 UI 显式提示，符合「不静默丢弃」 | 保持 |
| `SessionSnapshot` | 未标 `@Immutable`，且含 `List<ConversationTurn>` 字段；同文件顶部注释声称「消息模型标 @Immutable 让 Compose 可跳过」，`SessionSnapshot`/`WsData` 反而没标。`ChatStore.snapshot` 每次 WS 事件都可能换引用 → 顶栏、快捷提交、`isStructured` 等下游全部重组 | 给 `SessionSnapshot`、`WsData`、`ConversationTurn`、`ContentBlock.*` 统一标 `@Immutable`（数据类字段全 val，语义成立），并加稳定性测试（Compose compiler metrics 或 `WideLayoutMetricsTest` 同款断言） |
| `TurnUsage`/`TurnAuthor`/`EscalationRequest`/… | 解析容错与 camel/snake 兼容都到位 | 保持 |

### `ChatSessionEventReducer.kt`（530 行）

这是全仓最复杂的纯逻辑，逐函数结论：

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `applySnapshot` :47 | 「pending 设置不被快照反向覆盖」语义正确；`confirmedXxx` 与 `xxx` 双轨清晰 | 保持 |
| `reduce` :91 | 事件种类覆盖完整；`SessionEvent.Started -> Unit` 是刻意的（Started 由 SessionWatcher 用） | 保持 |
| `applyFullMessages` :128 | 五种合并路径（空快照不清屏 / 新窗口带截断 / 完全不相邻 / 重叠 / 首 turn 块级合并）都有注释解释；`coerceAtLeast(0)` 防护到位 | 保持。建议补 `incoming.isEmpty() && snapTotal > 0`（服务端真的清空了历史）这条分支的单测，现在只有 `isEmpty && total==0` |
| `applyIncrementalMessage` :251 | `expected == 0` 视为「服务端未下发 messageCount 就追加」，会让重复推送累积出重复 turn；靠 `last.role == update.message.role` 先替换兜住。风险仍在：同一 role 的**两条不同** user 消息在 increment 语义下会互相覆盖 | 增加按 `expectedCount` 严格校验：`expected in 1..current.messages.size` 时替换，否则追加，删掉 `expected == 0` 的兜底宽恕 |
| `mergeLeadingAssistantTurn` :400 | 按绝对块下标合并、逐块取内容更完整者，正确 | 保持 |
| `enrichConversationTimes` :489 | 每个 WS 事件都 `mapIndexed` 全量分配；返回值在无变化时保持同一引用（`if (changed) out else incoming`），下游 `!==` 短路有效 | 保持（可在 turns > 200 时加「只在首尾 2 条上补时间」的短路，属优化） |

### 其余 data 文件

| 文件/函数 | 结论 |
| --- | --- |
| `ServerProfiles.kt`（242 行） | 编解码、版本校验（`version != 2 → null` 让 `ServerStore` 回退旧镜像）、稳定 id、迁移逻辑都正确；`canonicalBaseUrl` 抛异常会被调用方 `runCatching` 兜住。**保持** |
| `TaskChanges.kt:8 changesTaskHierarchy` | 路径判定漏了 `/api/ai-teams/*/runs`、`/api/tasks/{id}` 等会改任务树的写入（PATCH `/api/wand-tasks/{id}` 已覆盖）。建议改成白名单表 + 单测，而不是逐条 `startsWith` |
| `ServerStore.java` | 见 §2（凭据明文 + 死写入） |
| `PtyTerminalSnapshot.kt` | `parse` 严格（缺字段直接整体拒绝，宁可走 resync）正确；`finalSize` 取 pending 里最后一次 resize 正确。**保持** |
| `SessionActivity.kt`/`ProviderRules.kt`/`WorkspaceModels.kt`/`TaskBoardModels.kt`/`MissionModels.kt`/`AiTeamModels.kt`/`WorkspaceRequestBodies.kt`/`MissionRequestBodies.kt`/`WorkspacePort.kt`/`WorkspaceLayoutReconciler.kt` | 逐函数读完，未发现缺陷；`WorkspaceLayoutReconciler` 的「保留 split/非会话 tab、丢已删会话」不变量与注释一致 |

---

## 2. `com.wand.app` 顶层（Kotlin + Java）

### `ServerStore.java`（302 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `readProfileStateLocked` :115 | 「legacy 指纹」三重校验（v2 有值 / 无指纹但投影一致 / 指纹不一致则重建）设计严谨，防住了「旧 APK 改过 key」的降级场景 | 保持 |
| `setDownloadedApkVersion` :252 | **只写不读**：无 getter，全仓 0 个读者，`UpdateManager.java:406` 写进去的版本串永远不会被任何人查询。`HomeActivity.checkUpdate` 的注释却说「已跳过或已下载的版本都静默处理」——注释与实现不符 | 删除该 key + setter + 调用点；或补 `getDownloadedApkVersion(channel)` 并在 `checkUpdate` 里真的用它抑制重复提示（二选一，需产品决策） |
| `getAppToken`/`saveServerProfile` | appToken 与 baseUrl 明文存 `SharedPreferences("wand_servers")`，且 `AndroidManifest.xml` 是 `android:allowBackup="true"`（未配 `dataExtractionRules`）→ 云备份/换机迁移会把连接码带出去 | 用 `androidx.security:security-crypto` 的 `EncryptedSharedPreferences`，或至少 `android:allowBackup="false"` + `android:dataExtractionRules` 排除该 prefs 文件 |
| `getNotificationSound*` | 见 P0-4 | 见 P0-4 |
| `getSkippedVersion`/`setSkippedVersion`/`setBetaChannel`/`setAppearanceMode`/`setHomeListMode`/`setKeepAliveEnabled`/`setHapticEnabled` | 都有一读一写，**保持** |

### `HomeActivity.kt`（601 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `onCreate` :59-450 | 单函数 ~390 行，内嵌 6 个本地函数（`installUpdate`/`installAlreadyApplied`/`asUpdateInfo`/`startDownload`/`checkUpdate`/`notificationPermissionGranted`），持有 12 个 `Activity` 级可变字段 | 抽 `HomeUpdateCoordinator(activity, store, manager, api)` 类（对齐 `QuickCommitStore`/`TaskListState` 既有风格），`onCreate` 只做装配 |
| `serverConnections = serverStore.serverProfiles.map { ... WandApi(profile.baseUrl, profile.token) }` :285 | 为**每个**已保存服务器预先构造 `WandApi`（连带 `clientFor` → `SSLContext` + OkHttpClient 实例）。有 8 台服务器就建 8 个 TLS 上下文，其中 7 个当次会话根本用不上 | 改成按需构造（`HomeServerConnection` 只存 profile，切换时再建）；或 `by lazy` |
| `installAlreadyApplied` :171 | `packageManager.getPackageInfo` 每次 ON_RESUME 都调（配合 `UpdateInstallReconciler.reconcile` 又是一次） | 合并成一次（`reconcile` 返回值复用） |
| `onTrimMemory` :479 | 只在 `TRIM_MEMORY_RUNNING_LOW+` 回收 STT 识别器，阈值合理 | 保持 |
| `onResume` :488 | 服务器变化 → 重建 Activity 的策略正确；`hasResumedRuntime` 首帧跳过避免误判 | 保持 |
| `onNewIntent` :518 | 用「启动全新实例 + finish」避免 `rememberSaveable` 导航栈串服务器——正确且必要 | 保持 |
| `disconnect` :566 | 先 `WandHttp.resetClient` 再 `removeServerProfile`：顺序正确（先让旧凭据不可用） | 保持 |
| `applyEdgeToEdge`/`isConfigurationNight`/`setKeepAlive`/`stopKeepAliveService`/`manageServers`/`switchServer` | 无问题 | 保持 |

### `SessionWatcher.kt`（477 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `start/stop` | 幂等 + 凭据变化重建 + stop 清空全部引用（含 helper/api/store），注释解释为什么不摘除进程级 observer——扎实 | 保持 |
| `registerForegroundTracking` :187 | 用 `ProcessLifecycleOwner` 并在注册后立刻同步 `currentState`（解决「注册晚于 STARTED 收不到回调」），正确 | 保持 |
| `scanTurns` :298 | 尾部 30 条窗口，避免 O(全量) 主线程扫描，正确 | 保持 |
| `setTodosRaw` :355 | 用 `toString()` 比较 JSONArray 判等——O(n) 序列化，但只在变化时赋值 | 可换 `SemanticTaskItem` 列表比较，属优化 |
| `sessions = HashMap<String, Watched>()` :103 | **无上限、无淘汰**。进程级单例，长时间使用后每个见过的会话都留一条 `Watched`（含 `todos` JSONArray） | `stop()` 时已 `clear()`，但同一连接内跨天不清理；建议 `handleEnded` 后延迟淘汰 + `sessions.size > 200` 时按 `status` 清一批 |
| `deliver`/`scopedNotificationTag` | `tag` 里插入 serverId 防跨服务器覆盖，正确 | 保持 |
| `contentIntent` :443 | 把 serverId 放进 `data`（PendingIntent identity 不含 extras），注释解释了根因，正确 | 保持 |

### `WandLog.kt`（360 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `install` :80 | 崩溃处理器链式调用 previous，不吞系统行为 | 保持 |
| `write` :137 | 内存环 + 落盘 + logcat 三份；`runCatching { Log.println }` 兜住 JVM 单测 | 保持 |
| `redact` :196 | 三条正则覆盖 token/password/bearer/authorization/cookie | 建议补 `X-APK-Sha256`? 不是凭据；补 `wand_session=...` cookie 形态（当前靠 `cookie:` 规则，但裸 `wand_session=xxx` 不匹配） |
| `FileSink.drainLoop`/`appendToFile` | 有界队列 + 批量追加 + 轮转一代，正确 | 保持 |
| `flushBlocking` :320 | `Thread.sleep(10)` 轮询等待，只在崩溃路径 | 保持 |

### `UpdateManager.java`（772 行）／`UpdateInstallReceiver.java`／`UpdateInstallState.kt`／`UpdateInstallReconciler.kt`

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `download(...)` 三个重载 :230/241/247 | 5 参、6 参重载**全仓 0 调用**（`HomeActivity.kt:212` 只调 7 参版）→ 死代码 | 删两个重载 |
| `downloadAttempt` :300 | 逐跳 origin 校验 + `.part` + SHA-256 + Content-Length + zip magic，防线完整；`Accept-Encoding: identity` 避免压缩长度歧义——很扎实 | 保持 |
| `installApk` :469 | 用 `MaterialAlertDialogBuilder`（legacy Java 弹窗）而其它 UI 已全面 Compose 化；`verifyWandDesignSystem` 只扫 `ui/` + ConnectActivity，所以这里漏网 | 把「安装未知应用」引导改为 Compose 侧 dialog（`UpdatePresentation.InstallFailed` 已有通道），或把该文件加进设计系统校验白名单并注明原因 |
| `handleActivityResult` :513 | 依赖已废弃的 `onActivityResult`（ZXing/权限都用 deprecated API）；Android 14+ 仍可用但会在未来版本移除 | 迁到 `ActivityResultLauncher`（`HomeActivity` 内注册 `StartActivityForResult`） |
| `sweepOnLaunch`/`purgeStaleApks`/`isNewerCoreVersion`/`coreVersion`/`sanitizeApkFileName`/`extractVersionFromFileName` | 逐函数读过 + 有 `UpdateManagerApkCleanupTest` 覆盖，逻辑正确 | 保持 |
| `UpdateInstallState.evaluate/isApplied/isCurrentSession` | 纯函数 + 单测 + 注释里写清了 versionCode 同号的坑；`isCurrentSession` 双向宽容正确 | 保持 |
| `UpdateInstallReceiver` | 会话号过滤、`setSelector(null)` 兼容小米、通知兜底、`ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED`，都是踩过坑的实现 | 保持 |

### `ConnectActivity.java`（792 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `saveActivateAndLaunch` :568 | 登录/探测**刚成功**就 `WandHttp.resetClient()`，把刚拿到的 session cookie 丢掉，迫使 `WandApp` 再登录一次（额外往返 + 触发服务端登录限流计数） | 去掉这一行；只在 `onRemoveServer`/`onClearServers`/`disconnect` 里 reset |
| `verifyConnectionInput`/`verifyServerProfile`/`testConnection*` | 连接码 / 已存 profile / 裸地址三条路径 + `http→https` 回退，逻辑闭合；错误文案分级（401 不可重试、429/5xx 可重试）正确 | 保持 |
| `tryAutoConnect`/`startAutoConnectAttempt`/`handleAutoConnectResult`/`scheduleAutoConnectRetry` | 「瞬时失败留在自动连接态」的退避（1s→10s，指数）合理；generation 守卫 + `isDestroyed` 双保险 | 保持 |
| `abortAutoConnect`/`cancelPendingConnectionForProfileMutation` | 用户点取消后丢弃迟到回调，注释解释了真实事故——正确 | 保持 |
| `handleDeepLink`/`onActivityResult`(扫码) | `wand://connect?url=` 与连接码/裸 URL 三态都处理了；扫码用 ZXing 的 `onActivityResult` | 扫码可迁 `ActivityResultContracts.StartActivityForResult` |
| `showCameraPermissionSettingsDialog` | 又是 `MaterialAlertDialogBuilder` | 迁 Compose（`ConnectComposeView` 已存在，加一个 dialog 通道即可） |

### 其余顶层文件

| 文件 | 结论 |
| --- | --- |
| `WandApplication.kt` | 生命周期回调 6 个空实现（接口要求），`onActivityResumed` 撤销 relaunch 通知 + `reconcile`；`logStartup` 输出上次异常退出摘要——**保持** |
| `NetworkStateTracker.java` | `onAvailable`/`onCapabilitiesChanged`/`onLost` 三态机 + `validated` 分离，注释解释了「available 不等于可上网」；**保持** |
| `NetworkErrorHelper.java` / `NetUtils.java` | 错误文案映射 + `isSameOrigin` 收紧 trust-all 扩散；**保持** |
| `WandDiagnostics.kt` | 报表拼装 / 写 Downloads / 写 URI / 分享，`withContext(IO)` 包裹，**保持** |
| `WandForegroundService.java` | `START_STICKY` + 通知停止按钮 + specialUse 类型声明，**保持**；建议补 `onTaskRemoved` 语义注释 |
| `WandShortcuts.kt` | 动态快捷方式按 serverId 过滤，**保持** |
| `QrScannerActivity/QrScannerOverlayView` | 相机预览 + 取景框绘制，无问题；`screenOrientation="portrait"` 与 `configChanges` 一致 |
| `NotificationHelper.java` | `evictStaleEntries`（>8 条且 5 分钟）、50ms 防抖、`setSilent(true)`（声音自己放）、`onErrorListener` 补 release——设计细致。**唯一问题**是 P0-4 的死配置与 `isValidSound` 死函数 |

---

## 3. `ui/` 状态层

### `ChatStore.kt`（785 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `isStructured` :140 | `snapshot?.isStructured ?: true`：快照未到时**默认结构化**。PTY 会话首屏未加载完就发送 → 走 `/api/sessions/:id/input`（结构化通道）而不是 PTY 双包提交 | 由导航参数注入（`ChatScreen(isStructured=... )`），或默认 false 并把「未知」当成禁用发送态 |
| `start` :145 | 三态（Skip/Reconnect/FirstConnect）解决「shutdown 后 started 挡住重进」的坑，注释清楚 | 保持 |
| `applyRealtimeState` :259 | 逐字段 `!=`/`!==` 短路，避免无变化写 Compose state——这道优化是聊天列表不掉帧的关键 | 保持 |
| `setModel/chooseThinkingEffort/chooseMode` :305/336/376 | 乐观更新 + `settingsMutationMutex` 串行 + generation 防旧响应覆盖 + `pendingXxxMutations` 计数让快照不反向覆盖，三处实现模式一致 | 保持；建议抽一个 `OptimisticSetting<T>` 泛型消除三份重复 |
| `send` :430 | 排队去重、失败回滚、`advanceSendPhase` 状态机、`respondImmediately = !queueing` 都正确 | 保持 |
| `sendPtyChatInput` :506 | `delay(30)` 魔法值分隔文本与 `"\r"`（依赖服务端不粘包） | 具名常量 + 注释来源（对齐 Web `sendTerminalChunks`），或改用「等服务端 accepted 回执再发 CR」 |
| `submitAskUser` :534 | 失败回滚 `submitted=false` 并恢复 `isResponding`，正确 | 保持 |
| `promoteQueued/editQueued/deleteQueued/clearQueued` :567 起 | 乐观 + 回滚 + `queuePromotePending` 防重复，正确 | 保持 |
| `resolvePermission` :650 | escalation 与 legacy 两条路径都先本地清态再请求，失败靠 `requestResync` 兜 | 保持 |
| `loadEarlierBlocks/loadEarlierTurns` :708/747 | 两阶段翻页 + 「起点被改动则不合并」的守卫，正确；`leadingVisibleCount` 跟随服务端 | 保持 |
| `stopResponding` :634 | PTY 分支发 ESC 后不本地置 `isResponding=false`（等 WS 回推），与结构化分支不一致但更安全 | 加一行注释说明为什么 |

### 其它 store

| 文件/函数 | 问题 | 整改 |
| --- | --- | --- |
| `SessionTitleStore`（`SessionTopic.kt:44-96`） | **只增不减**：`titles/generating/ptyBusy/permissionBlocked` 四个 map 无淘汰、无 TTL；`clear()` 全仓只被单测调用，生产从不清。会话删除后条目永久驻留；`permissionBlocked` 若因断线错过解除事件，首页会长期显示「等你」 | 在 `SessionWatcher.stop()` 与 `stopScreen` 时 `clear()`；`permissionBlocked` 加时间戳与 10 分钟 TTL；或改成 `MutableStateFlow<Map>` + LRU |
| `QuickCommitStore`（343 行） | 状态机（entryPhase/entryFeedbackToken）与 Web 语义对齐；`loadStatus` 静默失败符合「badge 不显示即可」；`finishEntrySuccess` 用 `delay(1_000)` 而非 `WandMotion` token——这是停留时长不是转场，可接受 | 保持；`delay(1_000)` 抽常量 |
| `ScopedStore` | `scope` 可重建 + `ensureScope()`，解决了 Compose 复用 store 时 `cancel` 过不能再 launch 的问题 | 保持 |
| `SessionDraftStore` / `SendFeedback` / `ThinkingEfforts` / `AppNav` | 读完无缺陷；`AppNav.Saver` 用 `\u0001` 分隔、旧 key（`new-session`/`workspaces`）保留为升级恢复路径（不是死代码）；`NavState.setDetail/closeSession/closeWorkspaceTask/syncTaskMembership` 的栈操作都有注释解释场景 | 保持 |
| `AppNav.HomeListMode.next` | 只有自己的单测引用，生产无调用 | 删（连同单测那 2 行断言） |

---

## 4. `ui/screens`（18,000 行，问题最集中）

### `ChatScreen.kt`（2851 行，`ChatScreen` 单函数 ~700 行）

| 位置 | 问题 | 整改 |
| --- | --- | --- |
| `ChatScreen` :264（`fun ChatScreen` 起，到 ~995 共 ~730 行） | 单函数持有 ~35 个 `remember`/`rememberSaveable` 状态 + 6 个 `LaunchedEffect` + 自定义 `NestedScrollConnection`，任何一处状态变更都要读完 700 行才能判断影响面 | 抽 `ChatScreenState`（滚动模式、贴底重试、锚点、展开态、附件、skeleton 状态）与 `ChatInteractionHandlers`，`ChatScreen` 只做布局装配；对齐 `TaskListState`/`QuickCommitStore` 既有分层 |
| `expandCurrentReplyToBottom` :547 | 硬编码 `listOf(50L, 150L, 350L, 700L)`，与 `chatStickToBottomRetryDelaysMs(listSettled = true)`（:253）**重复**。两处一旦不同步，「回到底部」和「流式贴底」时序就会不一致 | 改为 `chatStickToBottomRetryDelaysMs(listSettled = true)` |
| 贴底双 effect :507/:514 | 注释解释了「重试链不能挂在 messages 上（会被每个 chunk 取消重启）」——这是正确且必要的拆分 | 保持（这段是本文件最有价值的经验，建议提到 docs） |
| `ConversationTurnScrubber` :1127 | 每个刻度一个 `animateDpAsState`（N 个动画对象）+ `LocalConfiguration.screenWidthDp * 0.82` 估气泡宽度 | 刻度的宽窄变化改为单一 `Animatable`/`derivedStateOf`，或限制刻度数（>200 条会话时直接跳转）；宽度限制改为实际测量 |
| `QueueBar`/`QueueItemRow` :1584/1671 | 展开态用 `animateFloatAsState` 转箭头 + `AnimatedVisibility`，符合规则；但队列用 `WandIcons.history`（时钟/历史图标）表达「排队消息」，语义不符 | 换 `WandIcons.todo`/新增 queue 图标 |
| `InputBar` :1902 | 聚焦态「胶囊 ↔ 卡片」两态、`refocusAfterSend`、`draftNeedsExpanded` 溢出保护都正确 | 保持 |
| `TrailingSendStop`/`SubmitMorphButton` :2132/2196 | 规则 3/4 的标准实现：同一位置 发送中→已送达/失败，`WandInPlaceSwap` + `animateColorAsState`，`enabled` 只在 Send 态 | 保持（可作为其它模块的参照实现） |
| `shortModelLabel` :1559 | 硬编码 "opus"/"sonnet"/"gpt-5.5" 等模型名做缩写。模型换代就要改代码 | 改成「取 `label` 里 ` · ` 之前的主名 + 超长截断」的通用规则，去掉具体型号 |
| `voiceTapOrHoldGesture` :2606 | 轻点/按住 180ms 二分 + 上滑取消，`withTimeoutOrNull` 实现干净 | 保持 |
| `SttModelDownloadDialog`/`formatMb` :2777/2850 | `formatMb` 用 `Locale.US`，界面其它地方用默认 locale——不一致 | 统一 |

### `ChatBlocks.kt`（3188 行，单文件承载 40+ 个 Composable）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `TurnView` :190 | 折叠态默认 `initiallyCollapsed=false` 内部又 `mutableStateOf(false)`（`shouldCollapseReply` 恒 false），`initiallyCollapsed` 参数实际无效果 | 删掉该参数或让它真的生效（`rememberSaveable(initiallyCollapsed)` 已用参数做 key，但值被忽略——语义混乱） |
| `collapseActivityItems` :1800 | 「最后一段活动默认展开、更早收成状态条」+ `newest/running` 标记，逻辑正确；`activityGroupKey` 用 `startIndex + 首项 id` 做稳定 key，正确 | 保持 |
| `pairToolBlocks` :2230 | 先按 `tool_use_id` 精确配对、再邻接兜底、id 双方都有但不匹配时不抢配——这三条注释对应的都是真实 bug 场景 | 保持（这段是易错点，建议加更多单测：交错并行调用、缺失 id、孤儿 result） |
| `collectSubagentActivities` :1236 | 只有「最后一条用户消息之后」的任务参与运行/中断判定，避免分页截断误报——正确 | 保持 |
| `currentTodos`（`ChatActionBlocks.kt:844`） | 三种协议（TaskList 语义 / TodoWrite / TaskCreate+TaskUpdate 归并）兜底完整；`TaskCreate` 结果用正则 `Task #([^\s]+) created` 抽 id——**依赖英文文案**，服务端/上游改文案即失效 | 优先用 `tool_result` 里结构化字段；正则降级并在解析失败时打 `WandLog.w` |
| `SubagentActivityDock` :512 | pager 选中态与 `selectedAgentId` 双向同步 + `settledPage` 回流，边界（空列表/运行中优先）都处理了 | 保持 |
| `GeneratedAgentLogo`/`agentGemPalette`/`agentIdentityColor` :948/1084/1104 | `Path` 按像素尺寸 `remember` 缓存（避免每帧分配），`variant` 由 id 派生稳定——注释写了原因，是正确做法 | 保持 |
| `ActivityFoldCard` :1919 | 贴尾/离尾判定只信用户拖拽（`isScrollInProgress`），程序滚动不算——正确；两层 `LaunchedEffect` 有重复嫌疑（一个 `scrollTo` + 一个 `snapshotFlow(maxValue)` 收尾） | 合并为一个 effect |
| `ToolResultBody` :2821 | 按需加载完整内容 + 24k 字展示上限 + 错误态文案，正确 | 保持 |
| `TerminalCard` :641（`tween(900)` 在 :694） | `infiniteRepeatable(tween(900))` **硬编码时长**，违反「时长只从 WandMotion 取」 | 换成 `WandMotion` 新增的 `spin()` token（或用 `breath()`+rotation） |
| `SettingsScreen`（另见 §5） | 见下 | — |

### `ChatActionBlocks.kt`（1093 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `AskUserQuestionCard` :132 | 已答只读态按行拆答案、单选/多选指示器、提交禁用态都正确 | 保持 |
| `DiffCard` :386 | `kind`/`move_path`/`diff_unavailable_reason` 兜底完整；错误文案含 "haven't granted"/"permission" 的字符串匹配 → 服务端文案改动即失效 | 用 `result.isError` + 结构化字段（服务端已有 escalation）判定「等待授权」，字符串匹配只做兜底并打日志 |
| `UnifiedDiffBlock` :544 | `AnnotatedString` 按内容 + 颜色 `remember`（注释解释了 Compose 读取必须在组合期取其值）——正确 | 保持 |
| `TodoProgressBar` :940 | 呼吸动画走 `reduceMotionEnabled`，`activeTodoIndex` 推导 Codex 二态，正确 | 保持 |

### `TaskListScreen.kt`（1497 行，`TaskListScreen` 单函数 ~1000 行）

| 位置 | 问题 | 整改 |
| --- | --- | --- |
| 函数体 :136-195 | **50+ 个 `remember { mutableStateOf }`** 挤在一个 Composable 里（新建任务表单 20 个、目录选择器 5 个、重命名/清空/归档/删除确认 6 个、多选 4 个…） | 抽 `TaskListScreenState` 数据类 + `NewTaskFormState`（表单 20 个字段单独成类），Composable 只保留渲染；这是本文件最大的可维护性风险 |
| `allGroups/searchedGroups/visibleGroups/overview` :198-204 | **未 `remember`**：`directoryTreeGroups`（map+copy）、`homeSearchGroups`、`attentionOnlyGroups`、`homeOverview` 每次重组都全量重算——包括每 30s 的 tick、搜索框每次按键、任何 dialog 开关 | `remember(state.groups, searchQuery, attentionOnly) { ... }` 包起来 |
| `newTaskOpen` 分支 :433-720 | 团队派发/重试/父任务补关联三条链路的状态机嵌在 Composable 内，`submitNewTask` 局部函数 ~120 行 | 抽 `NewTaskComposerStore`（对齐 `QuickCommitStore` 的写法），纯函数 `newTaskNeedsCardCreation` 已有单测 |
| `directoryPickerContent` :377 + `pendingTarget` :966 | 两个不同的 `WandBottomSheet` **共用同一个 `targetSheetState`**，且目录选择器不调 `show()`、工作窗口选择器在协程里调 `show()` → sheet 状态互相干扰（一个 hide() 另一个受影响），行为不可预期 | 各自 `rememberModalBottomSheetState`；统一「先设置 visible 状态，再在 LaunchedEffect 里 show()」的写法 |
| `nowMillis` ticker :207 | 见 P0-3 | 见 P0-3 |
| `Toast.makeText(... "任务已创建，但启动会话失败" ...)` :592 | 提交链路的结果用 Toast 表达，违反动效规范「提交结果不用 Toast」 | 用对话框内的 error 槽（同文件 `MutationErrorText` 已有通道） |
| `SidebarManageBar` / `collectManagedIds` / `resolveManagedAction` / `describeManagedAction` | 批量操作「归档任务 + 删除终端」的语义与 Web 对齐，且区分危险/非危险样式 | 保持 |

### `TaskBoardScreen.kt`（2027 行）

| 函数 | 问题 | 整改 |
| --- | --- | --- |
| `refresh`/`taskChanges.collect`/6s 轮询 :192-253 | 用了 `repeatOnLifecycle(STARTED)`（正确）；`refreshMutex` 序列化；`awaitGeneratedBoardTaskTitle` 用 5 次退避轮询标题 | 保持 |
| `openCreateDialog`/`onCreate` :175/383 | 「建卡失败/交团队失败」重试只复用已有卡（`teamRetryTaskId`），注释解释了两个 id 的区别（看板卡 id ≠ workspace task id）——这是踩过坑的写法 | 保持 |
| `WandChoiceStrip` 状态筛选 :555 | 无问题 | 保持 |
| `BoardAgentDots` :1324（keyframes 在 :1340） | `keyframes` 用常量 `BOARD_AGENT_DOT_CYCLE_MS`，但仍是**自造动画规格**而非 WandMotion token | 移到 `WandMotion` 作为 `dots()`，或复用 `breath()` |
| 文件整体 2027 行 | 新建/编辑对话框、卡片、看板列、拖拽全在一文件 | 按「卡片 / 对话框 / 列」拆三个文件 |

### 其它 screens（结论）

| 文件 | 结论 |
| --- | --- |
| `PtyTerminalScreen.kt`（1226 行） | `requestDirectKeyboard()` 用 `delay(40)` + 翻转 `keyboardRequested` 骗 IME 重开（:206-216），`PtyDirectField` 又用 `delay(60)`/`delay(40)`（:844-846）、`PtyInputDrawer` 用 `delay(80)`（:990）——均为时序碰运气。建议统一走 `SoftwareKeyboardController.show()` + `WindowInsets.ime` 判定，把 4 个魔法延时收敛为 1 个可注释常量。其余（发送双包、ESC/Ctrl 组合、缩放持久化、选择栏）实现正确 |
| `NativeComposerSurface.kt`（177 行） | 规则 2 的正确实现（面板在输入行**上方**长出，输入行位置不动），`FilledComposerAction` 触控 44dp、视觉 32dp | 保持 |
| `HomeChrome.kt`（1333 行） | 搜索原位展开 + 图标变形 + 分段控件，全部走 WandMotionKit；`HomeWorkspaceCard/HomeTaskBlock/HomeSessionRow` 层级（15/13/14sp）有明确注释 | 保持；文件可拆（卡片/顶栏/启动条） |
| `CollapsedDirectoryRail.kt`（620 行） | 折叠窄栏 + `DirectoryPeekOverlay` 悬浮预览，`abs(anchorTop - top) > 0.5f` 抖动量判等 | 保持 |
| `TaskSessionTabStrip.kt`（519 行） | 一个 tab 条 519 行，含创建/删除/切换 | 可拆；逻辑本身正确 |
| `WorkspaceTaskScreen.kt` / `TaskBoardTaskScreen.kt` / `MissionsScreen.kt` / `AiTeamsScreen.kt` / `AiTeamDetailScreen.kt` / `WorkspaceTargetSheet.kt` / `SessionMoveSheet.kt` / `WorkspaceWorktreeReviewSheet.kt` / `NewTaskComposerScreen.kt` / `QuickCommitSheet.kt` | 逐函数读完，未发现正确性缺陷。`QuickCommitSheet` 的自定义磁吸拖拽（`pointerInputDock` + 六个 `Animatable`）是唯一「自己写物理」的地方，用了 `spring(dampingRatio=0.62f, StiffnessMediumLow)`（:1025）——同样应提取为 WandMotion token |
| `TaskListState.kt`（518 行） | 变更串行化、`pendingGroupOrder` 压制轮询旧顺序、`mutate` 统一错误文案、`CancellationException` 透传——设计到位。`startSync` 的轮询问题见 P0-3 |
| `TaskBoardPresentation.kt` / `TaskListPresentation.kt` / `HomePresentation.kt` / `AiTeamPresentation.kt` / `NewTaskComposerPresentation.kt` | 纯函数层，单测覆盖好，是仓库里质量最高的部分；仅 `groupHasLiveActivity`/`sessionIsRunning` 是死代码 |
| `WorkspaceWorkflow.kt`（214 行） | generation 守卫、创建期间不可 dismiss、布局 PUT 失败不回滚会话——不变量注释明确 | 与 `TaskListState.createTaskWindow` **重复实现**同一业务（创建工作窗口 + 写布局）；应合并到一处（`WorkspacePort` 扩展函数），否则两边行为会漂移 |
| `ChatMarkdown.kt`（677 行） | 块级解析（表格/围栏/列表/任务列表/引用）与内联解析（粗斜删除线/代码/链接）覆盖完整；`markdownLinkDestinationEnd` 处理 `<path>` 与括号文件名；未闭合标记按原文显示。**问题**：复制代码块/下载失败用 `Toast`（与全局 `WandSnackbarHost` 不一致）；`inlineMarkdown` 是 `@Composable` 且内部持 dialog 状态，每个段落一份 | Toast → `showWandNotice`；把「外链确认/文件预览」状态提到块级之上 |
| `ChatCopy.kt`/`ChatTime.kt`/`SendFeedback.kt`/`ComposerHelpers.kt` | 小工具，正确；`ComposerHelpers.readAttachment` 见 P0-1 |
| `SettingsScreen.kt`（1230 行） | 见 P0-4 与 P2；`SettingsScreen:124 motionEnabled` 是死变量 |
| `theme/Theme.kt`（566 行） | `WandMotion` token 完备（press/fast/normal/enter/exit/morph/indicator/breath/settleSpring/respectMotion）；`rememberReduceMotion()` 读 `ANIMATOR_DURATION_SCALE` + `prefers-reduced-motion` | 保持 |
| `theme/Glass.kt`（317 行） | `glassSurface` 的 backdrop/降级双路径、`edgeToEdge`/`drawRim` 开关、`glassCard` 对「半透明语义底在部分 GPU 上透白块」的处理都有注释；`AmbientBackground` 已简化 | 保持 |
| `components/*` | `WandMotionKit` 是动效规范唯一实现入口（morph/indicator/inlineSearch/inlinePanel/inPlaceSwap/dragReorder），设计正确；`Wand.kt`/`WandControls.kt`/`WandChrome.kt`/`WandIcons.kt`/`WandSnackbar.kt`/`WandStatusPresentation.kt`/`BrandLogos.kt` 无缺陷；`WandTeamRunPanel.kt` 正确 |

---

## 5. `ui/terminal` 与 `speech`

### terminal

| 文件/函数 | 问题 | 整改 |
| --- | --- | --- |
| `NativePtyTerminal.kt`（354 行） | `replayTerminalSnapshot`（RIS 重置 + resize + 快照 data + pending 顺序回放）正确；`handle` 里 `output` 分支「同步消费成功后才 ack」+ `finally` 里对跳过的帧补 ack，符合 Render 背压契约；`onClipboardCopy = {}` 明确拒绝 OSC 52（安全） | 保持 |
| `NativePtyTerminal.start/stop/reconnect/retry` | `rejectedSnapshot` 标记避免「快照不受支持」时反复显示 loading 遮罩——细节到位 | 保持 |
| `TerminalSelection.kt`（109 行） | **深度反射**进 termlib 私有字段（`SelectionManager`/`TerminalScreenState`/`getSelectionRange`…）取选择态与屏缓冲。库升级即碎 | ① `proguard-rules.pro` **已**为 `SelectionManager`/`SelectionMode`/`SelectionRange`/`TerminalScreenState`/`TerminalSnapshot`/`TerminalLine` 整类 keep（已核对），R8 侧无风险；② 真正的风险是库升级改字段/方法名——当前 `catch → Inactive/空串` 会**静默降级**（选择菜单不再出现、复制全部拿到空串），用户与开发者都无感；建议 catch 里 `WandLog.w` + 「复制全部」失败时提示「当前版本不支持」；③ 向上游提 issue 用 `SelectionController` 公开 API 替换反射 |
| `TerminalShortcuts.kt`（260 行） | xterm 键序列编码（CSI final / tilde / Ctrl+字母 / Shift 映射表）逐条正确，`normalizeTerminalBinding` 兜住非法键 | 保持 |
| `TerminalPaste.kt`（81 行） | bracketed paste 跨帧跟踪（保留未匹配尾巴）+ Codex 强制加括号 + payload 内 ESC 转义防提前闭合——正确 | 保持 |
| `TerminalAppearance.kt`（97 行） | 缩放吸附 0.25 步进、字体走 `CustomFallbackBuilder` 让 CJK 回落到系统 sans、失败回落 MONOSPACE | 保持 |
| `PtyScrollSlop.kt` + `tools/TermlibGesturePatch.java` | 运行期字节码补丁改滚动阈值——技术上很重，但注释写清了「不改库就慢滑触发选择」的根因，且补丁有 `@Keep` 契约 | 保持；建议在 `docs/` 留一份补丁说明（升级 termlib 时的检查点） |

### speech

| 文件/函数 | 问题 | 整改 |
| --- | --- | --- |
| `SherpaSpeechEngine`（345 行） | 录音循环、20 次读失败容忍、垫 0.5s 静音冲 lookahead、`@Volatile phase` 状态机、模型常驻 + 空闲回收（10 分钟/190MB）——逐函数正确；`finish()` 靠改 phase 让录音循环退出，简洁 | 保持 |
| `SpeechNativeLibrary`（159 行） | 自签 AAR + arm64 .so 双 SHA-256 校验、`setReadOnly` 后 `load`（Android 17 要求）、marker 文件校验、zip 炸弹防护（entry 大小上限）——安全实现扎实 | 保持 |
| `SttModelManager`（338 行） | 镜像优先 hf-mirror、按文件跳过已下载、Content-Length 校验、`.part` 原子改名、最大文件 ≥10MB 兜底、`pruneInvalidArtifacts` 一次性清扫 | **缺少内容哈希**：只校验字节数，被投毒的镜像返回同长度文件会通过。建议对每个文件固定 SHA-256（与 `SpeechNativeLibrary` 同款做法） |
| `VoiceInputController`/`VoiceSessionStateMachine`/`SystemSpeechEngine`/`SpeechEngine` | 状态机是纯逻辑 + 单测；final 1.5s 超时回落 partial；`listenerGeneration` 丢弃迟到回调 | 保持 |

---

## 6. 死代码清单（可直接删，附证据）

按仓库规范「确认无引用即删」，以下均有全仓引用计数证据（含 `test`/`androidTest`/`docs`/`xml`）：

| # | 位置 | 内容 | 证据 | 预估删减 |
| --- | --- | --- | --- | --- |
| 1 | `ui/components/WandInputSurface.kt` | 整个文件（`Modifier.wandInputSurface`） | 全仓仅声明处 1 次 | ~34 行（删文件） |
| 2 | `ui/components/WandMotionKit.kt:673` | `WandStatusRail` | 仅声明处 | ~11 行 |
| 3 | `ui/screens/TaskListPresentation.kt:53-64` | `groupHasLiveActivity` + 私有 `sessionHasLiveActivity` | 仅声明处（`sessionNeedsYou` 才是真在用的） | ~12 行 |
| 4 | `ui/screens/HomePresentation.kt:45` | `sessionIsRunning` | 仅声明处 | ~3 行 |
| 5 | `ui/screens/ChatBlocks.kt:1224` | `subagentStatusText`（注释自称「四态文案唯一出口」，实际无人调用） | 仅声明处 | ~7 行 |
| 6 | `ui/screens/ChatBlocks.kt:2206` | `truncateInline` | 仅声明处 | ~6 行 |
| 7 | `ui/screens/SettingsScreen.kt:124` + `:644` | `motionEnabled` 变量 + `rememberSettingsMotionEnabled()`（唯一调用点赋给未使用变量） | `motionEnabled` 在文件内仅出现 1 次 | ~15 行 |
| 8 | `NotificationHelper.java:42`、`:162` | `SOUND_PRESETS[][1]` 中文标签 + `isValidSound()` | 标签与函数全仓 0 引用（`SOUND_PRESETS` 只在删旧 channel 的循环里用 `[0]`） | ~10 行 |
| 9 | `UpdateManager.java:206-224` | `download()` 5 参与 6 参重载（实现体 `:230-245`） | 唯一调用点 `HomeActivity.kt:212` 用 7 参版（`:218`） | ~18 行 |
| 10 | `ServerStore.java:252` | `setDownloadedApkVersion` + key（无读者） | 全仓仅定义 + 1 处调用 | ~5 行 + 1 调用点 |
| 11 | `ui/screens/TaskListPresentation.kt:23` | `HomeListMode.next` | 只有 `TaskListPresentationTest.kt:511-512` 引用 | ~2 行 + 2 行测试 |
| 12 | `ui/screens/TaskListPresentation.kt:66-75` | `showsDirectoryDisclosure(directoryCount)` 形参未使用且恒真（`@Suppress("UNUSED_PARAMETER")`），`isDirectoryExpanded` 因此恒等于 `!userCollapsed` | 逻辑上不可达的 false 分支 | ~10 行 |
| 13 | `ui/screens/TaskListPresentation.kt:50` | `orderedTaskSummaries(tasks) = tasks` 恒等包装 | 无变换语义 | ~3 行 |

附带清理（非纯删，需判断）：

- `ui/SessionTopic.kt:44 SessionTitleStore.clear()`：生产 0 调用（仅单测）。要么在 `SessionWatcher.stop()` 里调用（推荐），要么删掉并把测试改成新入口。
- `ui/screens/ChatBlocks.kt` / `ui/screens/QuickCommitSheet.kt` / `ui/screens/ChatActionBlocks.kt` 里 4 处注释仍写「走 glassCard」，实现早已换成 `wandCardSurface`——注释过期。
- `HomeActivity.checkUpdate` 附近注释「已下载的版本都静默处理」与实现不符（见 P0-4/死代码 #10）。

清理方式（按仓库约定）：**一个提交只做删除**，不顺手改名；删完跑 `./gradlew :app:testDebugUnitTest` + `./gradlew :app:assembleDebug`，
UI 相关（#7）另附真机截图核对设置页与聊天页无变化；`WandStatusRail`/`wandInputSurface` 删除后 `verifyWandDesignSystem` 仍应通过。

---

## 6b. 清理执行记录（2026-09-27，已落地）

§6 表 1-13 全部已删，另附清理 3 项、顺带清理 6 个失效 import。共 17 个文件（含 1 个文件删除）。

| 删除内容 | 文件 |
| --- | --- |
| `wandInputSurface`（整个文件） | `ui/components/WandInputSurface.kt`（已 `git rm`） |
| `WandStatusRail` + `layout.width` / `shape.CircleShape` import | `ui/components/WandMotionKit.kt` |
| `subagentStatusText`、`truncateInline` | `ui/screens/ChatBlocks.kt` |
| `sessionIsRunning` | `ui/screens/HomePresentation.kt` |
| `orderedTaskSummaries`、`groupHasLiveActivity`+`sessionHasLiveActivity`、`showsDirectoryDisclosure`、`HomeListMode.next`、`WorkspaceTaskSummary` import、`isDirectoryExpanded` 去掉恒真参数 | `ui/screens/TaskListPresentation.kt` |
| `motionEnabled` + `rememberSettingsMotionEnabled()` + `android.provider.Settings` import | `ui/screens/SettingsScreen.kt` |
| `isValidSound()` + `SOUND_PRESETS` 中文标签列（改为 `LEGACY_SOUND_CHANNEL_SUFFIXES`） | `NotificationHelper.java` |
| `setDownloadedApkVersion()` | `ServerStore.java` |
| `download()` 5 参 / 6 参重载 + 只写不读的「已下载版本」记录（连带 `downloadAttempt` 的 `latestVersion`/`channel` 死参数） | `UpdateManager.java` |
| 调用点去掉两个死参数、修正与实现不符的注释 | `HomeActivity.kt` |
| 失效 import：`tween` / `LinearProgressIndicator` / `onSizeChanged` / `Image` / `imePadding` / `WandButton` | `ChatBlocks.kt`、`TaskListScreen.kt` |
| 两处调用点改用 `group.tasks` | `HomeChrome.kt`、`CollapsedDirectoryRail.kt`、`TaskListScreen.kt` |
| 过期「走 glassCard」注释（实现早已是 `wandCardSurface`） | `ChatActionBlocks.kt`、`QuickCommitSheet.kt` |
| `SessionTitleStore.clear()` 接入 `SessionWatcher.stop()`（原先生产 0 调用，四个 map 只增不减） | `SessionWatcher.kt` |
| 测试：`orderedTaskSummaries` 改测真实的 `directoryTreeGroups`（保留「不本地二次排序」这一回归守卫）；删 2 条恒真断言 + `HomeListMode.next` 断言；新增 `groupWithTasks` 助手 | `test/.../TaskListPresentationTest.kt` |

验证证据：

```
./gradlew :app:testDebugUnitTest :app:assembleDebug   -> BUILD OK（含 verifyWandDesignSystem 设计系统闸门）
SKIP_INSTALL=1 APK_DIST_DIR="$HOME/.wand/android" ./debug.sh  -> wand-v4.76.0-debug.09271209.apk（R8 分发构建）
GET /api/android-apk-update?currentVersion=0.0.0&channel=beta -> updateAvailable=true, latestVersion=4.76.0-debug.09271209,
    size=9458554, sha256=0c38be30f3f0…，本地文件 size/sha256 与响应一致，/android/download 返回 206 + PK 头
emulator-5554 安装启动 -> 登录成功 / WS 已连接 / GET /api/tasks 200 / GET /api/sessions 200，无 FATAL
设置页 uiautomator 树 -> 外观与反馈、界面主题、振动反馈、语音输入、服务器、更新等区块与版本号均正常
```

**未做（属缺陷修复，需产品/行为决策）**：P0-1 附件 OOM、P0-2 请求取消、P0-3 轮询生命周期、P0-4 通知声音开关、及各 P1 项。

**提交状态**：本次清理叠加在仓库当前未提交的 AI 团队 / 新建任务 WIP 之上（34 个改动文件 + 11 个新增文件），因此没有提交——
`TaskListScreen`/`ChatBlocks`/`HomeChrome`/`QuickCommitSheet`/`ChatActionBlocks`/`TaskListPresentation`/`SettingsScreen` 等文件里既有你的 WIP 改动，
也有本次删除，混在一个提交里会把 WIP 一起带走。建议先提交或暂存 WIP，再让我按「一个提交只做删除」拆分。

---

## 7. 规范偏差（动效 / 交互 / 一致性）

| 规范条目 | 现状 | 整改 |
| --- | --- | --- |
| 「时长/曲线只从 WandMotion 取」 | `ChatActionBlocks.kt:694 infiniteRepeatable(tween(900))`；`TaskBoardScreen.kt:1340 keyframes{...BOARD_AGENT_DOT_CYCLE_MS}`；`QuickCommitSheet.kt:1025 spring(0.62f, StiffnessMediumLow)`；`UpdateSheet.kt:171 fadeIn(tween(WandMotion.normal))`（用时长 token 但默认曲线，未走 `tweenEnter()`） | 在 `WandMotion` 增加 `spin()`/`dots()`/`dockSpring()`，四处替换；`UpdateSheet` 改 `WandMotion.tweenEnter()/tweenExit()` |
| 规则 3「提交结果不用 Toast/弹窗」 | `TaskListScreen.kt:592` 启动会话失败用 Toast；`ChatMarkdown.kt:231`（复制代码块）/`:473`（文件下载失败）用 Toast，而同 App 其它处用 `showWandNotice` | 统一到 `WandSnackbarHost.showWandNotice`（提交链路的错误留在对话框 error 槽） |
| 规则 4「同组件实例承载两态」 | `SubmitMorphButton`/`WandMorphIconButton`/`ComposerActionsMenu` 已正确 | 保持 |
| 规则 5「指示条前缘先走、后缘晚一拍」 | `WandMotion.indicator/trailDelay` + `WandSegmentedTrack` 已实现 | 保持 |
| 规则 7「列表项当前页展开」 | `HomeWorkspaceCard`/`HomeTaskBlock` 用 `AnimatedVisibility` 原位展开，`TaskListExpansionStore` 持久化 | 保持 |
| 无障碍 | 大量装饰性 `contentDescription = null`（合理），但 `WandIconButton`/`ControlChip` 之外的裸 `Modifier.clickable` 缺少 `role`/`onClickLabel`（如 `TaskListScreen.DirectoryPickerRow`、`InlineError` 的「重试」、`ChatBlocks` 的 `ExpandChevron` 容器） | 给交互元素补 `role = Role.Button` 与语义标签（`ChatBlocks` 的卡片折叠头已做，可作为范例） |

---

## 8. 建议的整改批次（每批一个提交，可独立验收）

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| B1 安全与崩溃 | P0-1 附件读取上限 + 丢弃可见化；`ServerStore` token 加密/禁备份；STT 模型哈希校验 | `:app:testDebugUnitTest`；真机选一个 >10MB 文件确认提示而非崩溃 |
| B2 生命周期与取消 | P0-2 OkHttp 取消；P0-3 三处轮询接入 `repeatOnLifecycle` | 真机：进首页→息屏 1 分钟→看 `WandLog` 无轮询记录；退出聊天页后抓包无 180s 请求仍在跑 |
| B3 连接与配置 | `normalizeBaseUrl` 返回 null + 非法地址不可重试；去掉 `saveActivateAndLaunch` 里的 `resetClient`；P0-4 通知声音（补齐 UI 或删死配置，需产品决策） | 连接页输入 `http://` → 明确报错不转圈；设置页静音生效 |
| B4 死代码 | §6 表 1-13 + `SessionTitleStore.clear()` 接线 | 删除后 `assembleDebug` + `verifyWandDesignSystem` 通过；`git diff --stat` 只减不增 |
| B5 状态层重构 | 把 `ChatStore` 里 `setModel/chooseThinkingEffort/chooseMode` 三份重复的「乐观更新 + generation 守卫」抽成 `ObservableSetting<T>`；`TaskListScreenState`/`NewTaskComposerStore`/`HomeUpdateCoordinator` 抽类；两个 bottom sheet 拆 state | 纯重构，UI 行为不变，补 `NewTaskComposerPresentation` 相关单测保持绿 |
| B6 规范对齐 | §7 四项 token 化 + Toast 收敛 + 无障碍 role | `docs/motion-design.md` 自检清单逐条过；真机截图对照 |
| B7 文档 | 把 `ChatScreen` 贴底双 effect、`pairToolBlocks` 配对规则、`PtyScrollSlop` 补丁、`UpdateManager` 完整性三道防线整理进 `docs/` | 评审通过 |

**按仓库约定，任何 B1-B6 的 Android 改动收尾都必须重新编译带版本号的 beta APK 并部署到 `~/.wand/android/`，并验证
`/api/android-apk-update?currentVersion=0.0.0&channel=beta` 返回新版本。** 本次审计未改动任何代码，故不涉及构建与分发。

---

## 附：验证与复现命令

```bash
cd android
./gradlew :app:testDebugUnitTest          # 单测（54 个测试文件 / 424 个 @Test）
./gradlew :app:assembleDebug              # 触发 verifyWandDesignSystem 设计系统闸门
./gradlew :app:lintDebug                  # 静态检查
# 死代码复核（应只剩声明处）：
grep -rn "WandStatusRail\|wandInputSurface\|subagentStatusText\|truncateInline\|groupHasLiveActivity\|sessionIsRunning" app/src
# 配置项读写复核：
grep -rn "setDownloadedApkVersion\|setNotificationSound\|setNotificationVolume" app/src
```
