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
import java.util.concurrent.Executor
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7MediaArtworkTest {
    @Before fun resetProviderPathCache() {
        // Robolectric 为每条用例建立不同目录，AndroidX 静态路径缓存不能跨用例共享。
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache").clear()
    }
    private class Queue : Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private val worker = Queue()
    private val main = Queue()
    private val context = RuntimeEnvironment.getApplication()
    private val published = mutableListOf<Uri?>()
    private val logs = mutableListOf<String>()
    private val covers = L7MediaArtwork(context, worker, main, logs::add, published::add)
    private fun image() = ByteArrayOutputStream().also { output ->
        Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).let {
            it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle()
        }
    }.toByteArray()

    @Test fun onlySelectedTransferPublishesAndUriCanBeReadUntilDisconnect() {
        covers.select(1); covers.submit(2, image()); worker.drain(); main.drain()
        assertNull(published.last())
        covers.submit(1, image()); worker.drain(); main.drain()
        val uri = published.last()!!
        assertEquals("content", uri.scheme)
        assertTrue(context.contentResolver.openInputStream(uri)!!.use { it.read() >= 0 })
        covers.close()
        assertFalse(java.io.File(context.cacheDir, "l7-media-artwork").walkTopDown().any { it.isFile })
    }
    @Test fun decodedButUndeliveredArtworkIsDeletedWhenOwnerCloses() {
        covers.select(1); covers.submit(1, image()); worker.drain()
        covers.close(); main.drain()
        assertEquals(listOf<Uri?>(null), published)
        assertFalse(java.io.File(context.cacheDir, "l7-media-artwork").walkTopDown().any { it.isFile })
    }

    @Test fun mediaResetInvalidatesUndeliveredImagesAndReusedTransferIds() {
        covers.select(1); covers.submit(1, image()); worker.drain(); main.drain()
        assertNotNull(published.last())
        covers.submit(2, image()); worker.drain()
        covers.reset(); covers.select(1); main.drain()
        assertNull(published.last())
        covers.select(2)
        assertNull(published.last())
        covers.submit(1, image()); worker.drain(); main.drain(); covers.select(1)
        assertNotNull(published.last())
        covers.close()
    }

    @Test fun switchingToUncachedTransferClearsOldCoverAndReturningRestoresIt() {
        covers.select(1); covers.submit(1, image()); worker.drain(); main.drain()
        val first = published.last()!!
        covers.select(2)
        assertNull(published.last())
        assertNull(covers.selectedUri(1))
        covers.select(1)
        assertEquals(first, published.last())
        assertEquals(first, covers.selectedUri(1))
        covers.close()
    }

    @Test fun diagnosticsCorrelateTransferAndFileWithoutUriOrImageContents() {
        covers.select(7); covers.submit(7, image()); worker.drain(); main.drain()
        val key = L7MediaArtworkProvider.diagnosticKey(published.last()!!)
        assertTrue(logs.any { it.contains("receive transferId=7") && it.contains("accepted=true") })
        assertTrue(logs.any { it.contains("cache result=SAVED coverKey=$key") })
        assertTrue(logs.any { it.contains("accept transferId=7 coverKey=$key") && it.contains("available=true") })
        assertFalse(logs.any { it.contains("content://") || it.contains(context.cacheDir.absolutePath) })
        covers.submit(999, image()); covers.submit(8, ByteArray(0)); worker.drain(); main.drain()
        assertTrue(logs.any { it.contains("transferId=999") && it.contains("accepted=false") })
        assertTrue(logs.any { it.contains("decode result=EMPTY_OR_INVALID") })
        covers.close()
    }
}
