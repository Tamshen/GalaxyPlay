package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7MediaArtworkProviderTest {
    @Before fun resetProviderPathCache() {
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache").clear()
    }
    @Test fun providerLogsFileOpenAndRemovedFileFailureWithoutReadingImageContents() {
        val app = RuntimeEnvironment.getApplication()
        L7DebugLog.buffer.clear()
        var selected: Uri? = null
        val direct = Executor { it.run() }
        val covers = L7MediaArtwork(app, direct, direct) { selected = it }
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).let {
                it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle()
            }
        }.toByteArray()
        covers.select(1); covers.submit(1, bytes)
        val uri = selected!!
        val key = L7MediaArtworkProvider.diagnosticKey(uri)
        app.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
        assertTrue(L7DebugLog.buffer.snapshot().lines.any { it.contains("artworkRead coverKey=$key result=OPENED") })
        covers.close()
        try {
            app.contentResolver.openInputStream(uri)?.close()
            fail("已删除图片不得继续打开")
        } catch (_: java.io.FileNotFoundException) { }
        val lines = L7DebugLog.buffer.snapshot().lines
        assertTrue(lines.any { it.contains("artworkRead coverKey=$key result=FAILED") })
        assertFalse(lines.any { it.contains("content://") || it.contains(app.cacheDir.absolutePath) })
    }

    @Test fun arbitraryUriTextNeverBecomesDiagnosticKey() {
        assertEquals("unknown", L7MediaArtworkProvider.diagnosticKey(Uri.parse("content://own/private-song.jpg")))
        assertEquals("unknown", L7MediaArtworkProvider.diagnosticKey(Uri.parse("content://own/12345678.jpg")))
    }
}
