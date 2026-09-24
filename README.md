# Android 客户端

此目录用于放置 wand 的 Android 客户端工程。PTY 使用 Compose + libvterm 原生终端：
订阅服务端 `/ws` 的 `terminalState` 快照与 PTY 原始输出，通过 `pty_input`、
`pty_resize`、`pty_ack` 双向通信，不加载 WebView。软键盘的按键直接写入 PTY；
底部草稿框用于整段提示、语音和附件。显示使用内置 JetBrains Mono（OFL）和暖色
ANSI 调色板。终端仿真依赖 `org.connectbot:termlib:0.0.10`（Apache-2.0；锁定与
Kotlin 2.2 / compileSdk 36 兼容的版本）。

## 约定

- Android 工程代码放在此目录中。
- APK 构建产物**不要提交到仓库**。
- 本地 debug 分发包默认放在 `dist/apk/`，服务端通过 `config.json` 里的 `android.apkDir` 指向它。

## 参考文档

- [同步 iOS 移动端体验改动（2026-06-18）](docs/ios-mobile-updates-reference-2026-06-18.md)：历史设计参考（其中的 PTY WebView/embed 已由原生终端替代）。

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

其中 `X.Y.Z` 来自当前仓库最新的 `v*` tag，和线上 GitHub Action 的正式版本基准保持一致。

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

## 按住说话（端侧语音识别）

聊天输入栏左侧麦克风按钮：按住录音 → 气泡实时转写 → 松手把文字**追加**进输入框（不覆盖草稿）→ 上滑取消。交互协议对齐 Web 端 voice-btn / iOS `SpeechRecognizerService`（覆盖式完整文本，非增量）。

代码在 `app/src/main/java/com/wand/app/speech/`：

| 文件 | 职责 |
|------|------|
| `SpeechEngine.kt` | 引擎接口（start / finish / cancel，回调 onPartial / onFinal / onError） |
| `SystemSpeechEngine.kt` | 系统 `SpeechRecognizer`（API 31+ 有端侧服务时用 `createOnDeviceSpeechRecognizer`，否则默认识别器 + `EXTRA_PREFER_OFFLINE`） |
| `SherpaSpeechEngine.kt` | sherpa-onnx 流式 Zipformer-CTC 中文模型，完全离线；识别器常驻复用 |
| `SttModelManager.kt` | 用户确认后下载语音引擎与模型（模型从 hf-mirror 优先 / huggingface 兜底下载，中文模型约 26 MB → `filesDir/asr/`） |
| `SpeechNativeLibrary.kt` | 从固定版本的官方 GitHub AAR 按需下载 arm64 JNI，校验 SHA-256、只读落入 `noBackupFilesDir` 后 `System.load` |
| `VoiceInputController.kt` | 按住会话状态机 + 引擎选择 |

**引擎优先级**：sherpa 本地模型 + 已下载引擎 → 系统识别器（GMS 设备）→ 弹出启用对话框。未启用本地语音时，APK 不含 sherpa 原生库，不会自动下载；确认启用才下载约 38 MB 的官方 AAR，提取约 22 MB arm64 库，同时按需下载所选模型。已有模型的升级用户只需下载引擎一次。国产无谷歌服务 ROM 上系统识别器普遍不可用（OPPO 返回 false、华为挂假服务），因此对话框会提供本地路径；官方 GitHub 不可达时提示错误而不是运行未校验的库。启用后转写完全离线。

**构建说明**：`app/libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar` 是仓库内锁定的构建依赖（38 MB），Gradle 只提取 API 类（替换上游两个强制 `loadLibrary` 的 wrapper），不把 AAR 或 `.so` 放进 APK；APK 仅带 arm64 相关其他小型库。升级 sherpa 时须同时更新 AAR、wrapper JNI 签名以及 `SpeechNativeLibrary` 固定版本/文件大小/SHA-256。DEX 使用 `useLegacyPackaging = true` 压缩，优先降低自分发下载体积（安装时可能额外占用磁盘）。本地端侧验收运行 `cd android && ./gradlew :app:connectedDebugAndroidTest`（不带分发版本参数，测试变体需保留测试运行器的 Kotlin 类）。

## 后续演进

后续可扩展为从 GitHub Release 自动拉取最新 APK 到运行时目录，再继续复用同一个下载入口。
