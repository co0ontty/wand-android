# Android 客户端

此目录用于放置 wand 的 Android 客户端工程。PTY 使用 Compose + libvterm 原生终端：
订阅服务端 `/ws` 的 `terminalState` 快照与 PTY 原始输出，通过 `pty_input`、
`pty_resize`、`pty_ack` 双向通信，不加载 WebView。软键盘的按键直接写入 PTY；
底部草稿框用于整段提示和附件。显示使用内置 JetBrains Mono（OFL）和暖色
ANSI 调色板。终端仿真依赖 `org.connectbot:termlib:0.0.10`（Apache-2.0；锁定与
Kotlin 2.2 / compileSdk 36 兼容的版本）。构建会改写它的触摸判定：滚动按按下点的
累计距离计算，避免慢滑被当成文字选择；长按后的复制、粘贴和整行在客户端里补上。

## 新建对话与员工消息

- 员工「发消息」进入稳定私聊；输入区的工具、模型与思考深度是本页临时配置，不修改员工定义，不改写既有运行。
- 全局、工作区与目录新建共用待发送对话页；默认不归属任务，可显式新建或绑定任务。看板「新建任务」使用同一页并预选任务归属。
- 打开、取消与本地选择附件不创建资源；首次提交才创建所需任务及空会话，再由现有 composer 接管首发。已接受的创建结果用于续接，未知送达按服务器与稳定入口保存，关闭重开或修改工具/模型不会解锁；配置未加载成功时不能创建。
- 显式新任务可选择隔离 worktree；新建或绑定任务的会话使用服务端确认的真实目录。
- 任务列表直接打开有效的当前聊天或终端，多工作窗口通过切换入口访问；切换替换当前详情，一次返回回到原列表。空任务保留新建入口。

## 约定

- Android 工程代码放在此目录中。
- APK 构建产物**不要提交到仓库**。
- 本地 debug 分发包默认放在 `dist/apk/`，服务端通过 `config.json` 里的 `android.apkDir` 指向它。

## 本地分发流程

1. 在 `android/` 中完成客户端打包：

```bash
./debug.sh
# 或只产出本地分发包、不安装到设备：
SKIP_INSTALL=1 ./debug.sh
```

`debug.sh` 会生成：

```text
dist/apk/wand-vX.Y.Z-debug.MMDDHHMM.apk
```

其中 `X.Y.Z` 取 Android 仓库与所在主仓库的最高正式语义 tag（`vX.Y.Z`），避免子仓标签晚于主仓发布时生成更低版本、被旧 Beta 包遮蔽。独立检出只取 Android 自身标签；不创建或推送标签。

2. 在 wand 服务端 `config.json` 中打开 Android APK 分发，并把目录指向本子模块的 `dist/apk/`：

```json
{
  "android": {
    "enabled": true,
    "apkDir": "/Users/you/path/to/wand/android/dist/apk",
    "currentApkFile": ""
  }
}
```

`apkDir` 也可以写相对路径；相对路径会按 wand 的配置目录解析。

3. 启动 wand 后，设置页“关于”中会显示 APK 下载入口。

4. Android App 设置页开启“Beta 通道”后，会接收 `-debug.*` 本地构建；关闭后只提示正式 `X.Y.Z` 包。版本排序与 `app/build.gradle` 的 `versionCode` 一致：`X.Y.Z < X.Y.Z-debug.* < X.Y.(Z+1)`，也就是当前线上 tag 后的新 commit 用本地 dev 版本测试，后续下一个线上 tag 仍能正常覆盖升级。

如需固定当前下载文件名，可设置 `currentApkFile`；一般本地测试保持空字符串，让服务端自动扫描目录中版本号最新的 APK。

## 地址连接、内网发现与完整设置

- 输入服务器地址后可直接填写密码，不要求连接码或额外认证选项。点击已保存地址或发现的内网服务时，未设置密码的服务直接连接；需要密码则原位展开输入框。密码只用于当次登录，保存的是服务端签发的 App token。
- 连接页只读发现当前 Wi-Fi / 有线内网的 Wand 服务：扫描当前 IPv4 网段（最多本地 `/24`）的 8443 及已保存私网端口，最多 30 秒，离开页面即取消；自定义端口也可手动输入。发现阶段不发送凭据、不登录、不扫描公网/移动数据/VPN。
- 设置页「完整 Web 设置」直接打开当前服务器的 `/settings` 全屏网页，不经过首页、不模拟点击设置弹窗，也不替换原生聊天/终端。直接复用客户端认证，无权限面板、无第二次密码。WebView 使用当前端点的登录 cookie 与绑定到该登录的设置访问凭据；凭据不回写原生 CookieJar，客户端登录撤销/过期后立即失效。登录确实失效时返回客户端重连，不在网页再输密码。返回键直接回原生设置，关闭后清理 WebView 登录态，网页管理权限不回写原生会话。旧服务需先升级以支持 `/settings`。

DEX 使用 `useLegacyPackaging = true` 压缩，优先降低自分发下载体积（安装时可能额外占用磁盘）。本地端侧验收运行 `./gradlew :app:connectedDebugAndroidTest`（不带分发版本参数，测试变体需保留测试运行器的 Kotlin 类）。

## 后续演进

后续可扩展为从 GitHub Release 自动拉取最新 APK 到运行时目录，再继续复用同一个下载入口。

## 诊断日志导出

设置页「诊断」提供三条落盘路径，都不依赖「分享」这一环（分享面板里的接收方不一定有
「保存到文件」）：

| 入口 | 行为 | 实现 |
|------|------|------|
| 保存到「下载」 | 一次点击直接落盘 `下载/Wand/wand-android-log-*.txt` | `MediaStore.Downloads`（`IS_PENDING` 归零后立即可见），无需存储权限、不弹选择器 |
| 另存到其他位置… | 系统「另存为」自选目录 / 云盘 | `ACTION_CREATE_DOCUMENT`（SAF），选完位置才拼报表 |
| 分享日志文件 | 交给系统分享面板（微信 / 邮件…） | `FileProvider` + `ACTION_SEND`，写 `cacheDir/exports/` 只留最近 5 份 |

日志本体落在 `filesDir/logs/wand.log`（512 KB 轮转一代，最多 2 份），崩溃时同步写盘。
口径：下载与另存都写完整报表；分享走缓存文件。

报表内容（`WandDiagnostics.buildReport`）：

| 段落 | 来源 | 用途 |
|------|------|------|
| 环境头 | 版本 / versionCode / 机型 / 系统 / 进程启动时间 / 服务器 origin | 判断「哪个版本、什么设备、连的哪台服务」 |
| 历史异常退出 | `ActivityManager.getHistoricalProcessExitReasons` | Java 崩溃、原生崩溃（含 tombstone）、ANR 的原因、时间与 trace —— 崩溃后进程已死，这是唯一能拿回现场的地方 |
| 运行时日志 | `WandLog` 落盘文件 + 内存环 | 网络 / WebSocket / 更新安装 / 会话加载等关键事件时间线 |
| logcat 快照 | 当前进程 `logcat -d --pid` | 补足原生库与系统侧日志；无权限时该段留空 |

打点位置：`WandApi`（每个 REST 请求的方法、路径、状态码、耗时、响应体大小与错误）、
`WandAuth`（登录结果）、
`WandSocket`（连接、重连、resync、序号间隙）、`SessionWatcher`（通知中枢连接与列表刷新）、
`ChatStore`（会话打开、快照、发送失败）、`ConnectActivity`（连接探测）、`UpdateManager` /
`UpdateInstallReceiver`（检查、下载、安装状态回调）。

脱敏：`WandLog.redact` 兜底清掉 `token` / `password` / `apiKey` / `Authorization` / `Cookie`
与 `Bearer` 凭据；**会话 id 故意保留**，它是排查故障的锚点。任何新增打点都不得主动传凭据。

## 结构化会话的块级窗口

长任务的单条 assistant turn 可能有几百个内容块（一次 `2000` 行的 `Bash` 输出加几十个工具
调用就是一个块），按「整条 turn」下发会拉回 MB 级首屏载荷，在弱网/弱机上表现为“会话打开很
慢或打不开”。Android 与 Web / iOS 走同一套块级窗口：

- `WandApi.getSession(blockBudget = WandApi.CHAT_BLOCK_WINDOW)`（`60`）与
  `WandSocket.blockBudget` 都在 `subscribe` 里声明预算；服务端只回最近这么多块，并附
  `leadingBlockOffset` / `leadingBlockTotal`。
- `ChatStore.loadEarlier()` 两阶段：先把 `messages[0]` 被切掉的头部按块翻（
  `GET /api/sessions/:id/messages?turn=&blockOffset=&blockLimit=`，每页 40 块），
  翻完了再按整条 turn 往前翻更早的会话（`?offset=&limit=`）。顶部入口文案会区分两者。
- 合并规则在 `ChatSessionEventReducer.applyFullMessages`（对齐 iOS `SessionMessageReducer`）：
  init / resync / 全量快照按**绝对块下标**与本地已加载内容求并集，重叠 turn 逐块取更完整的
  一版，所以服务端每次只回尾窗也不会把用户刚翻出来的旧前缀冲掉。

不带 `blockBudget` 的客户端仍按 turn 级窗口下发；通知中枢（`SessionWatcher`）不需要首屏内容，
保持不带预算。

## 更新安装与回到新版本

点「安装更新」后由系统安装器接管，Android 会杀掉 Wand 进程，用户只看到系统安装器的「完成」页。
客户端用一组持久化状态把这段流程接起来（`ServerStore` 的 `update_*` 键 + `UpdateInstallState`）：

1. 提交安装会话前记录「待安装版本」（读 APK 自身的 versionName/versionCode）与时间；
2. 界面立即从「已下载」切到**正在安装**：旧界面不再留一个可点的「安装更新」，也就不会出现
   「点完又让我安装一次」的错觉；
3. 缺少通知权限时先申请：通知是安装完成后把用户带回应用的**唯一可靠通道**；
4. `UpdateInstallReceiver` 收到 `STATUS_SUCCESS` 后先试一次直接 `startActivity`（部分给了
   「后台弹出界面 / 自启动」权限的 OEM 系统会放行），然后必发一条**「更新已完成」通知**，
   点它即由系统 UI 代发起进入新版本。任意 Activity 恢复时会撤销通知与残留 PendingIntent；
5. 回到前台时 `UpdateInstallReconciler` 结算状态：版本已生效 → 清账并记日志；过了宽限期仍未
   生效（用户取消 / 失败）→ 清账，界面退回可重试；安装被取消后重进的界面会明确显示
   「上次安装没有完成，可以重试」。

**为什么不做自动重启进程**：Android 10+ 的后台启动限制（BAL）会拦下所有后台拉起，实测
Android 16 上「直接 startActivity」和「AlarmManager + PendingIntent 闹钟兜底」都是 `BAL_BLOCK`
（闹钟那条曾经被当成豁免路径，日志证明 AlarmManager 以
`MODE_BACKGROUND_ACTIVITY_START_DENIED` 发送），把进程杀掉只会让用户看到应用自己退出。
因此只保留：直接拉起（尽力而为）+ 通知（可靠）+ 界面里的「重新打开应用」按钮。

注意 `X.Y.Z-debug.MMDDHHMM` 这类连续 debug 构建共享同一个 versionCode，所以「装上了没有」不能
只看 versionCode —— 同号时必须再比 versionName，否则「还没装」会被误判成「已升级」。

失败与取消都会记录到诊断日志（含 PackageInstaller 状态码与中文说明），排查「更新后没重启」
时先导出日志。

## 本机固定构建入口

已配置的这台 Mac 使用 `~/android.sh`，外层不要加 sudo：

```bash
~/android.sh --check  # 检查桌面用户、外盘、JDK/SDK 与全局分发配置
~/android.sh          # 顺序执行单测、Beta 构建、全局部署与更新接口校验
```

入口复用 `~/ios.sh` / `~/start.sh` 的现有认证助手，以原桌面用户构建。沿用 `debug.sh` 的 tag 版本、签名与外盘守卫，先输出到外盘，再原子部署到 `~/.wand/android/`，同步 `.last-debug-version` 并记录 `.last-build-verification.json`（版本、大小、SHA-256、端点核对）。默认不安装/启动设备、不重启服务。`~/.config/wand-build/android-build.lock` 防重复运行；中断保留锁，须核对相关进程后处理，不能直接删锁或并行运行其他 Gradle 入口。此脚本是本机配置，不是其他机器的必需依赖；通用构建仍用仓库 `debug.sh`。
