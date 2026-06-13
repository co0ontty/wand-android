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
