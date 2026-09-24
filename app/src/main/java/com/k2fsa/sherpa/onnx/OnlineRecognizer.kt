package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

/**
 * JNI wrapper compatible with sherpa-onnx v1.13.2 (kotlin-api/OnlineRecognizer.kt).
 * The upstream wrapper eagerly calls System.loadLibrary() in its static initializer.
 * SpeechNativeLibrary loads the verified on-demand .so before this class is used instead.
 */
class OnlineRecognizer(
    assetManager: AssetManager? = null,
    val config: OnlineRecognizerConfig,
) {
    private var ptr: Long = if (assetManager != null) {
        newFromAsset(assetManager, config)
    } else {
        newFromFile(config)
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(hotwords: String = ""): OnlineStream = OnlineStream(createStream(ptr, hotwords))
    fun reset(stream: OnlineStream) = reset(ptr, stream.ptr)
    fun decode(stream: OnlineStream) = decode(ptr, stream.ptr)
    fun isEndpoint(stream: OnlineStream) = isEndpoint(ptr, stream.ptr)
    fun isReady(stream: OnlineStream) = isReady(ptr, stream.ptr)
    fun getResult(stream: OnlineStream): OnlineRecognizerResult = getResult(ptr, stream.ptr)

    private external fun delete(ptr: Long)
    private external fun newFromAsset(assetManager: AssetManager, config: OnlineRecognizerConfig): Long
    private external fun newFromFile(config: OnlineRecognizerConfig): Long
    private external fun createStream(ptr: Long, hotwords: String): Long
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun isEndpoint(ptr: Long, streamPtr: Long): Boolean
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun getResult(ptr: Long, streamPtr: Long): OnlineRecognizerResult
}
