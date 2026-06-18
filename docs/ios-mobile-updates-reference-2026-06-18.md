# Android 参考：同步 iOS 移动端体验改动

> 日期：2026-06-18  
> 范围：Android 子项目 `android/`。本文只总结 iOS / Web 侧本轮移动端改动，并给 Android 侧实现参考，不代表 Android 已完成全部同步。  
> 测试基准：iOS 真实 App 连接本机 `8443` 服务，在 iOS Simulator 中验证。

## 一句话结论

本轮 iOS 侧主要完成了四组移动端体验对齐：

1. PTY 会话改成原生外壳：中间只保留深色终端 WebView，底部输入栏走原生组件。
2. PTY 原生输入发送修正：发送文本后补 `\r`，真正触发终端回车；保留 PTY 快捷操作悬浮窗。
3. 快速提交体验统一：默认半屏 sheet + 可上滑展开，Chat / PTY 顶栏都能打开快速提交。
4. 设置页新增外观设置：明亮 / 黑暗 / 跟随系统，使用原生三段式 segmented 控件并即时生效。

Android 侧已有不少基础件：`PtyTerminalScreen.kt`、`QuickCommitSheet.kt`、`QuickCommitStore.kt`、`SettingsScreen.kt`、`WandTheme`。建议不要重写一套，而是在现有组件上补齐行为和抽公共组件。

## 1. PTY 原生会话

### iOS 侧最终行为

- `PtySessionView` 不再直接打开整张网页版。
- 顶部导航、provider 徽标、标题、工作目录、底部输入栏都由 SwiftUI 原生渲染。
- 中间 WebView 只加载终端黑窗：`embed=terminal`。
- 当启用原生输入栏时，URL 额外带 `nativeInput=1`，网页隐藏自己的 `.input-panel`。
- PTY 中间终端区域保持深色；周边原生 chrome 跟随 App 主题。
- PTY 快捷操作悬浮窗继续保留，不隐藏 joystick / shortcut panel。
- 发送逻辑不是 `text + "\n"`，而是先写入文本，再发送 `"\r"`，并带 `shortcutKey: "enter_text"`。

### Android 对应文件

- `app/src/main/java/com/wand/app/ui/screens/PtyTerminalScreen.kt`
- `app/src/main/java/com/wand/app/ui/ChatStore.kt`
- `app/src/main/java/com/wand/app/data/WandApi.kt`
- Web 端协议依赖服务端页面的 `embed=terminal&nativeInput=1`

### Android 建议实现

`PtyTerminalScreen.kt` 当前已经有原生顶栏 + 终端 WebView。下一步建议对齐 iOS 的“原生输入栏”方向：

- WebView URL 加上 `nativeInput=1`。
- WebView 中只展示终端主体和悬浮快捷操作，不展示网页输入栏。
- 在 Compose 底部添加原生输入栏，视觉用当前 Android 输入栏 / `WandGlass` 风格，而不是再画一套网页样式。
- PTY WebView 容器继续固定深色背景，外层页面、顶栏、底栏跟随主题 token。
- 保留快捷操作悬浮窗，不要用 CSS 或 WebView 注入隐藏它。

PTY 发送建议用和 iOS 一致的两段式：

```kotlin
suspend fun sendPtyChatInput(sessionId: String, text: String) {
    api.sendInput(id = sessionId, input = text, view = "chat")
    delay(30)
    api.sendInput(
        id = sessionId,
        input = "\r",
        view = "chat",
        shortcutKey = "enter_text",
    )
}
```

如果 Android 现有 `WandApi.sendInput` 没暴露 `view` / `shortcutKey`，优先补 API 参数，不要在 UI 层拼接换行字符串。

## 2. 快速提交入口与弹层

### iOS 侧最终行为

- ChatView 和 PTY 页右上角都显示 Git 变更入口。
- 入口展示 `~修改 -删除 +新增`，点击打开 `GitQuickCommitView`。
- 快速提交 sheet 默认半屏高度，足够显示核心操作区。
- 顶部显示系统 drag handle，可上滑展开到大状态。
- 关闭 sheet 后刷新 git status，提交后的计数能回到最新状态。
- Git 变更按钮抽成共享组件，避免 Chat / PTY 两边重复维护。

### Android 对应文件

- `app/src/main/java/com/wand/app/ui/screens/ChatScreen.kt`
- `app/src/main/java/com/wand/app/ui/screens/PtyTerminalScreen.kt`
- `app/src/main/java/com/wand/app/ui/screens/QuickCommitSheet.kt`
- `app/src/main/java/com/wand/app/ui/QuickCommitStore.kt`

### Android 建议实现

Android 当前 `ChatScreen.kt` 里已有私有 `GitChangesButton`，`QuickCommitSheet.kt` 也有 `QuickCommitSheet`。建议做两件事：

1. 把 `GitChangesButton` 移到可复用位置，例如 `QuickCommitSheet.kt` 或 `ui/components/GitChangesButton.kt`。
2. 在 `PtyTerminalScreen.kt` 顶栏右侧也接入同一个按钮和 `QuickCommitStore`。

建议接口形状：

```kotlin
@Composable
fun GitChangesButton(
    quickCommit: QuickCommitStore,
    onClick: () -> Unit,
)
```

PTY 页接入时：

- `remember(sessionId) { QuickCommitStore(sessionId, api) { toast -> ... } }`
- `DisposableEffect(quickCommit) { onDispose { quickCommit.shutdown() } }`
- 顶栏右侧放 `GitChangesButton(quickCommit) { quickCommit.openPanel() }`
- 页面底部或根 Box 里渲染：

```kotlin
if (quickCommit.panelOpen) {
    QuickCommitSheet(
        qc = quickCommit,
        isHapticEnabled = actions.isHapticEnabled,
        onDismiss = { quickCommit.closePanel() },
    )
}
```

弹层高度建议调整：

- 目前 Android `QuickCommitSheet` 使用 `skipPartiallyExpanded = true`，会直接展开较大状态。
- 为了对齐 iOS，本轮建议改为允许半展开：`skipPartiallyExpanded = false`。
- 使用 Material3 默认 drag handle 或 `BottomSheetDefaults.DragHandle()`，不要自绘一条线。
- 半屏状态要能看到：标题、git 概览、message/tag 输入、Commit/Tag/Push/Sub 操作区、提交发射区。

## 3. 设置页外观选择

### iOS 侧最终行为

- 设置页新增“外观”分组。
- 使用系统三段式 segmented 控件：`明亮 / 黑暗 / 跟随系统`。
- 默认值是 `跟随系统`。
- 设置存本地：`wand.appearanceMode`。
- 明亮 / 黑暗立即覆盖整个 App。
- 跟随系统会解除手动覆盖，并随系统外观变化实时更新。
- 设置页整体更贴近系统 Form：信息行用原生行布局，按钮着色交给系统角色和 tint。

### Android 对应文件

- `app/src/main/java/com/wand/app/ui/screens/SettingsScreen.kt`
- `app/src/main/java/com/wand/app/ui/theme/Theme.kt`
- `app/src/main/java/com/wand/app/HomeActivity.kt`
- `app/src/main/java/com/wand/app/ServerStore.java`

### Android 建议实现

Android 主题目前在 `WandTheme` 中直接读 `isSystemInDarkTheme()`：

```kotlin
MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
    content = content,
)
```

建议新增一个本地枚举：

```kotlin
enum class WandAppearanceMode {
    Light,
    Dark,
    System,
}
```

持久化位置建议放 `ServerStore` 或专门的 client preferences store，key 可对齐 iOS：

```text
wand.appearanceMode
```

`WandTheme` 改为接收模式：

```kotlin
@Composable
fun WandTheme(
    appearanceMode: WandAppearanceMode,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearanceMode) {
        WandAppearanceMode.Light -> false
        WandAppearanceMode.Dark -> true
        WandAppearanceMode.System -> systemDark
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}
```

如果 Activity 里仍需要让系统 bars / WebView / Dialog 跟随，可在 `HomeActivity` 或 root composable 同步：

- `AppCompatDelegate.setDefaultNightMode(...)`，如果项目已引入 AppCompat。
- 或保留纯 Compose 方案，同时用 `enableEdgeToEdge` / system bar style 按 `dark` 更新。

设置页 UI 建议使用 Material3 原生 segmented：

```kotlin
SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    val options = listOf(
        WandAppearanceMode.Light to "明亮",
        WandAppearanceMode.Dark to "黑暗",
        WandAppearanceMode.System to "跟随系统",
    )
    options.forEachIndexed { index, (mode, label) ->
        SegmentedButton(
            selected = appearanceMode == mode,
            onClick = { actions.setAppearanceMode(mode) },
            shape = SegmentedButtonDefaults.itemShape(index, options.size),
        ) {
            Text(label, maxLines = 1)
        }
    }
}
```

注意点：

- 不要自绘 underline / 胶囊切换器；用户明确希望像会话列表顶部那个系统切换。
- 当前 Android 设置页有 `WandCard` 和玻璃风格，可以保留，但主题选择控件本身尽量用 Material3 系统组件。
- 文案与 iOS 保持一致：“选择明亮、黑暗，或跟随系统外观。”

## 4. WebView 与嵌入协议

### iOS 侧最终协议

- `embed=terminal`：网页只渲染终端嵌入模式。
- `nativeInput=1`：网页隐藏自己的输入栏，输入由原生 App 负责。
- 原生 App 会给页面标记 iOS 壳环境，网页据 query/class 控制布局。
- PTY 中间终端背景固定深色。
- 非 PTY WebView 需要跟随 App 当前主题。

### Android 建议

Android WebView 侧也建议遵守同一协议：

- PTY 原生输入模式加载：

```text
{serverUrl}?session={sessionId}&embed=terminal&nativeInput=1
```

- 不要通过 Android 注入 JS 去删 DOM；优先让网页自己根据 query/class 隐藏 input panel。
- PTY WebView `setBackgroundColor(Color.BLACK)` 可保留。
- 非 PTY WebView / fallback 页面需要按当前主题设置背景，避免加载前白屏或暗色下闪白。

## 5. 验证清单

建议 Android 同步后至少跑以下手测：

1. 设置页默认显示“跟随系统”。
2. 在 Android 系统浅色下选择“黑暗”，设置页、会话列表、聊天页立即变暗。
3. 选择“明亮”，设置页、会话列表、聊天页立即变亮。
4. 选择“跟随系统”，切系统深色 / 浅色，App 跟随变化。
5. 打开结构化 Chat，会看到右上角 Git 变更入口，点击打开快速提交 sheet。
6. 打开 PTY 会话，同样能从右上角打开快速提交 sheet。
7. 快速提交 sheet 默认不是全屏，顶部有 drag handle，可上滑展开。
8. PTY 页面中间终端保持深色，周边 chrome 跟随主题。
9. PTY 浮动快捷操作窗口仍显示。
10. PTY 原生输入栏发送“Reply with OK only”这类消息时，终端实际收到回车并开始执行。

构建验证：

```bash
cd android
./gradlew :app:assembleDebug
```

真实服务验证仍建议使用本机 `8443`，不要只测 Web 页面；这类问题重点在原生 App / WebView / 软键盘 / 系统主题交互。

## 6. Android 拆分建议

推荐按下面顺序拆 PR：

1. `appearance-mode`：设置页外观三段式 + `WandTheme` 支持手动 light/dark/system。
2. `quick-commit-pty-entry`：抽共享 `GitChangesButton`，Chat / PTY 顶栏共用，sheet 允许半展开。
3. `pty-native-input`：WebView 加 `nativeInput=1`，底部改原生输入栏，发送 text + `\r`。
4. `webview-theme-sync`：普通 WebView 背景与主题同步，PTY WebView 保持深色。

每个 PR 都尽量保持一个行为闭环，方便真机验证和回滚。
