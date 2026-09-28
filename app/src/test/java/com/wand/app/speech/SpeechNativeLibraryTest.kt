package com.wand.app.speech

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
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
        val path = System.getProperty("wand.sherpaAar")
        assumeTrue("Optional official artifact check requires -PSHERPA_AAR", path != null)
        val archive = File(requireNotNull(path))
        assertTrue("Explicit official AAR must exist", archive.isFile)
        val target = File(temp.root, "native/libsherpa-onnx-jni.so")
        SpeechNativeLibrary.installFromArchive(archive, target)
        assertEquals(22_304_376L, target.length())
        assertFalse("Dynamically loaded library must be read-only", target.canWrite())
        assertTrue(File(target.parentFile, ".complete").isFile)
    }

    private val entryPath = "jni/arm64-v8a/libsherpa-onnx-jni.so"
    private val bytes = "verified fixture".toByteArray()
    private val fixture = NativeArchiveEntry(
        entryPath, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )

    private fun archive(name: String, path: String = entryPath, content: ByteArray = bytes): File {
        val archive = temp.newFile(name)
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(path))
            output.write(content)
            output.closeEntry()
        }
        return archive
    }

    @Test fun verifiedFixtureIsPublishedReadOnlyWithExactMarker() {
        val target = File(temp.root, "native/libsherpa-onnx-jni.so")
        installVerifiedNativeEntry(archive("valid.aar"), target, fixture)
        assertTrue(target.readBytes().contentEquals(bytes))
        assertFalse(target.canWrite())
        assertEquals(fixture.sha256, File(target.parentFile, ".complete").readText())
        assertFalse(File(target.parentFile, "${target.name}.part").exists())
    }

    @Test fun rejectsWrongHashEvenWithCorrectSizeAndPreservesPreviousBinary() {
        val target = File(temp.root, "native/libsherpa-onnx-jni.so")
        installVerifiedNativeEntry(archive("first.aar"), target, fixture)
        val altered = bytes.copyOf().also { it[0] = 0 }
        assertRejected(archive("altered.aar", content = altered), target)
        assertTrue(target.readBytes().contentEquals(bytes))
    }

    @Test fun rejectsWrongSizeAndIgnoresTraversalEntry() {
        val target = File(temp.root, "native/libsherpa-onnx-jni.so")
        assertRejected(archive("short.aar", content = byteArrayOf(1, 2, 3)), target)
        assertRejected(archive("traversal.aar", path = "../escaped.so"), target)
        assertFalse(File(temp.root, "escaped.so").exists())
        assertFalse(target.exists())
    }

    private fun assertRejected(archive: File, target: File) {
        try {
            installVerifiedNativeEntry(archive, target, fixture)
            fail("Unverified binary must not be installed")
        } catch (_: IOException) {
            assertFalse(File(target.parentFile, ".complete").exists())
            assertFalse(File(target.parentFile, "${target.name}.part").exists())
        }
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
