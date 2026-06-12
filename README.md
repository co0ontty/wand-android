# Android 客户端

此目录用于放置 wand 的 Android 客户端工程。

## 约定

- Android 工程代码放在此目录中。
- APK 构建产物**不要提交到仓库**。
- 页面下载入口使用的是 wand 运行时配置目录中的 APK，而不是本目录下的文件。

## 本地分发流程

1. 在 `android/` 中完成客户端打包。
2. 将生成的 APK 手工复制到 wand 配置目录下的 Android 工件目录。
   - 默认目录：`<configDir>/android/`
   - 默认 `configDir` 通常是 `~/.wand/`
3. 如需固定当前下载文件名，可在 `config.json` 中设置：

```json
{
  "android": {
    "enabled": true,
    "apkDir": "android",
    "currentApkFile": "your-app.apk"
  }
}
```

4. 启动 wand 后，设置页“关于”中会显示 APK 下载入口。

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
