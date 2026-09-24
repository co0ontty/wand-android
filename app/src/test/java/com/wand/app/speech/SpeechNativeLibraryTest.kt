package com.wand.app.speech

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechNativeLibraryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun wrappersDoNotLoadNativeCodeAtClassInitialization() {
        // The upstream AAR wrappers would throw UnsatisfiedLinkError here on a JVM without .so.
        Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
        Class.forName("com.k2fsa.sherpa.onnx.OnlineStream")
    }

    @Test fun installsOnlyVerifiedArm64LibraryFromPinnedAar() {
        val archive = File(requireNotNull(System.getProperty("wand.sherpaAar")))
        assertTrue("Pinned AAR must be available in the repo", archive.isFile)
        val target = File(temp.root, "native/libsherpa-onnx-jni.so")
        SpeechNativeLibrary.installFromArchive(archive, target)
        assertEquals(22_304_376L, target.length())
        assertFalse("Dynamically loaded library must be read-only", target.canWrite())
        assertTrue(File(target.parentFile, ".complete").isFile)
    }

    @Test fun rejectsUnverifiedLibraryWithoutLeavingACompleteMarker() {
        val archive = temp.newFile("untrusted.aar")
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("jni/arm64-v8a/libsherpa-onnx-jni.so"))
            output.write(byteArrayOf(1, 2, 3))
            output.closeEntry()
        }
        val target = File(temp.root, "native/libsherpa-onnx-jni.so")
        try {
            SpeechNativeLibrary.installFromArchive(archive, target)
            fail("Unverified binary must not be installed")
        } catch (_: IOException) {
            assertFalse(target.exists())
            assertFalse(File(target.parentFile, ".complete").exists())
        }
    }
}
