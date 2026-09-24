package com.wand.app.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** On-device check: remote pinned artifact → verified extraction → System.load from app data. */
@RunWith(AndroidJUnit4::class)
class SpeechNativeLibraryInstrumentedTest {
    @Test fun downloadAndLoadVerifiedNativeLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!SpeechNativeLibrary.isInstalled(context)) {
            SpeechNativeLibrary.download(context, OkHttpClient(), {}, { false })
        }
        assertTrue(SpeechNativeLibrary.isInstalled(context))
        SpeechNativeLibrary.ensureLoaded(context)
        Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
        Class.forName("com.k2fsa.sherpa.onnx.OnlineStream")
    }

    @Test fun downloadedModelCanDecodeWithDynamicallyLoadedLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!SpeechNativeLibrary.isInstalled(context)) {
            SpeechNativeLibrary.download(context, OkHttpClient(), {}, { false })
        }
        val model = SttModelManager.MODEL_ZH_SMALL
        if (!SttModelManager.isReady(context, model)) {
            SttModelManager.startDownload(context, model)
            val deadline = System.currentTimeMillis() + 240_000L
            while (!SttModelManager.isReady(context, model) && System.currentTimeMillis() < deadline) {
                val state = SttModelManager.state
                if (state is SttModelManager.State.Failed) fail(state.message)
                Thread.sleep(500)
            }
        }
        assertTrue("Model must finish downloading", SttModelManager.isReady(context, model))
        SpeechNativeLibrary.ensureLoaded(context)
        val dir = SttModelManager.modelDir(context, model)
        val recognizer = OnlineRecognizer(
            assetManager = null,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
                enableEndpoint = false,
            ),
        )
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(FloatArray(16000), 16000)
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                recognizer.getResult(stream)
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }
}
