package com.wand.app.ui.components

import android.content.Intent
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in isolated native preview only; no AI or real session mutation. */
@RunWith(AndroidJUnit4::class)
class SharedPlushSceneInstrumentedTest {
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
    @Test fun actualOfflineWebSceneRendersAndAnimatesFileProps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".preview"))
        val activity = instrumentation.startActivitySync(Intent().setClassName(context.packageName, "com.wand.app.ChatPreviewActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", "reading"))
        try {
            var web: WebView? = null
            repeat(120) {
                instrumentation.runOnMainSync { web = findWebView(activity.window.decorView) }
                if (web == null) Thread.sleep(100)
            }
            assertNotNull("Native Compose preview must own one real WebView", web)
            fun evaluate(expression: String): String {
                val done = CountDownLatch(1); var result = ""
                instrumentation.runOnMainSync { web!!.evaluateJavascript(expression) { result = it; done.countDown() } }
                assertTrue("Bounded local JS response", done.await(5, TimeUnit.SECONDS)); return result
            }
            var ready = false
            repeat(150) { if (!ready) { ready = evaluate("document.title==='scene-ready'") == "true"; if (!ready) Thread.sleep(100) } }
            assertTrue("Actual WebGL scene must reach ready, not silently stay PNG", ready)
            println("Scene viewport: " + evaluate("JSON.stringify({viewport:[innerWidth,innerHeight,devicePixelRatio],canvas:document.querySelector('canvas').getBoundingClientRect().toJSON(),body:document.body.getBoundingClientRect().toJSON()})"))
            assertEquals("true", evaluate("(()=>{const c=document.querySelector('canvas'),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;for(let i=3;i<d.length;i+=4)if(d[i]>128)n++;return n>50})()"))
            assertEquals("\"reading\"", evaluate("document.querySelector('canvas').dataset.fileActivity"))
            assertEquals("\"none\"", evaluate("getComputedStyle(document.querySelector('#thought')).display"))
            val firstFrame = evaluate("Number(document.querySelector('canvas').dataset.frame)").toInt()
            Thread.sleep(700)
            val nextFrame = evaluate("Number(document.querySelector('canvas').dataset.frame)").toInt()
            assertTrue("Continuous native WebView animation produces new real frames", nextFrame > firstFrame)
            assertEquals("true", evaluate("Number(document.querySelector('canvas').dataset.filePropPose)>0.9"))
            assertEquals("true", evaluate("window.__wandPlushDiagnostics().contexts<=1"))
            instrumentation.runOnMainSync {
                assertFalse(web!!.settings.allowFileAccess)
                assertTrue(web!!.settings.blockNetworkLoads)
                println("Native scene: ${web!!.width}x${web!!.height}, shown=${web!!.isShown}, hardware=${web!!.isHardwareAccelerated}")
            }
            Thread.sleep(500)
            val screen = instrumentation.uiAutomation.takeScreenshot()
            val point = IntArray(2)
            instrumentation.runOnMainSync { web!!.getLocationOnScreen(point) }
            val crop = android.graphics.Bitmap.createBitmap(screen, point[0], point[1], web!!.width, web!!.height)
            var darkPixels = 0
            for (y in 0 until crop.height) for (x in 0 until crop.width) {
                val pixel = crop.getPixel(x, y)
                if (android.graphics.Color.red(pixel) < 180 && android.graphics.Color.green(pixel) < 180 && android.graphics.Color.blue(pixel) < 180) darkPixels++
            }
            assertTrue("Actual native screenshot must contain the visible 3D character, not a blank canvas", darkPixels > 50)
            java.io.File(context.getExternalFilesDir(null), "scene-native-crop.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
