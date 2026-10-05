package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7MediaArtworkTest {
    private class Queue : Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private val worker = Queue()
    private val main = Queue()
    private val context = RuntimeEnvironment.getApplication()
    private val published = mutableListOf<Uri?>()
    private val covers = L7MediaArtwork(context, worker, main, published::add)
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
}
