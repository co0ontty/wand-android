# Android 客户端

此目录用于放置 wand 的 Android 客户端工程。

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
| `SttModelManager.kt` | 模型按需下载（hf-mirror 优先 / huggingface 兜底，两个裸文件免解压，约 26 MB → `filesDir/asr/`） |
| `VoiceInputController.kt` | 按住会话状态机 + 引擎选择 |

**引擎优先级**：sherpa 本地模型（已下载）→ 系统识别器（GMS 设备）→ 弹模型下载对话框。国产无谷歌服务 ROM 上系统识别器普遍不可用（OPPO 返回 false、华为挂假服务），sherpa 路径就是为它们准备的主路径。

**依赖说明**：`app/libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar`（38 MB，提交在仓库里）。不走 JitPack（k2-fsa 最新 tag 在 JitPack 构建失败、国内可达性不可控），钉本地文件保证本地 / publish.sh / CI 三种构建一致。`abiFilters` 只保留 arm64-v8a；`useLegacyPackaging = true` 把 .so 压缩进 APK（下载体积 +9 MB 左右）。升级 AAR 时从 sherpa-onnx GitHub Release 下载同名 static-link 版本替换并同步改 build.gradle 文件名。

## 后续演进

后续可扩展为从 GitHub Release 自动拉取最新 APK 到运行时目录，再继续复用同一个下载入口。
