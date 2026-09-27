# Proguard / R8 rules for wand Android client (release minify + shrinkResources)

# ── sherpa-onnx 端侧语音（JNI 关键）─────────────────────────────────
# native 方法靠 JNI 按全限定类名/方法名绑定，R8 改名或裁剪会让绑定失效、运行时崩溃。
# 整包保留 sherpa 类与成员，并保留所有 native 方法所在类的名字。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── OkHttp / Okio（可选平台依赖的告警抑制）──────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx-coroutines / Compose / ZXing 均自带 consumer rules，无需在此重复。

# termlib 手势补丁按这个类名和签名调用。R8 改名后，库里的 invokestatic 会找不到方法。
-keep class com.wand.app.ui.terminal.PtyScrollSlop {
    public static void noteDown(java.lang.Object, long);
    public static float displacementSquared(java.lang.Object, long);
}

# 长按菜单通过这些公开类型读取选区和屏幕文字。改名后菜单无法判断是否正在拖动。
-keep class org.connectbot.terminal.SelectionManager { *; }
-keep class org.connectbot.terminal.SelectionMode { *; }
-keep class org.connectbot.terminal.SelectionRange { *; }
-keep class org.connectbot.terminal.TerminalScreenState { *; }
-keep class org.connectbot.terminal.TerminalSnapshot { *; }
-keep class org.connectbot.terminal.TerminalLine { *; }
-keep class org.connectbot.terminal.TerminalLine$Cell { *; }
