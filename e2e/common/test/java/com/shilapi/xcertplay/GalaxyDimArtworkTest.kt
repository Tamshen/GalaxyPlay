package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyDimArtworkTest {
    @Before fun clearPaths() {
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
    }
    @Test fun cachesFileCopiesKeepsFourAndReleasesOnlyOwnFolder() {
        val app = RuntimeEnvironment.getApplication()
        val storage = object : ContextWrapper(app) {
            override fun getExternalMediaDirs() = arrayOf(File(app.cacheDir, "synthetic-external-media"))
        }
        val direct = Executor { it.run() }
        val input = L7MediaArtwork(app, direct, direct, {}, {})
        val dim = GalaxyDimArtwork(storage)
        val copied = mutableListOf<File>()
        try {
            repeat(5) { id ->
                val bytes = ByteArrayOutputStream().also { stream ->
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let {
                        it.eraseColor(id); it.compress(Bitmap.CompressFormat.PNG, 100, stream); it.recycle()
                    }
                }.toByteArray()
                input.select(id); input.submit(id, bytes)
                val original = input.selectedUri(id)!!
                val uri = dim.prepare(original)!!
                val file = File(uri.path!!)
                copied.add(file)
                assertTrue(file.isFile)
                assertEquals(uri, dim.prepare(original))
                assertArrayEquals(app.contentResolver.openInputStream(original)!!.use { it.readBytes() }, file.readBytes())
            }
            assertFalse(copied.first().exists())
            assertEquals(4, copied.count { it.exists() })
            GalaxyTemporaryFiles.cleanupDim(storage)
            assertEquals(4, copied.count { it.exists() })
            assertNull(dim.prepare(Uri.parse("content://other/private")))
        } finally { dim.close(); input.close() }
        assertTrue(copied.none { it.exists() })
    }
}
