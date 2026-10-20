package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyDimMediaBridgeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val events = LinkedBlockingQueue<String>()
    private val sent = LinkedBlockingQueue<CarPlayNowPlaying>()
    private val unbound = LinkedBlockingQueue<Boolean>()
    private val owner = AtomicReference<String?>(app.packageName)
    private val binds = AtomicInteger()
    private val connected = AtomicReference<ServiceConnection>()
    private val context = object : ContextWrapper(app) {
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(GalaxyDimMediaProbe.SERVICE, intent.component!!.className)
            binds.incrementAndGet()
            connected.set(connection)
            connection.onServiceConnected(ComponentName(app, "synthetic"), Binder())
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound.offer(true) }
    }
    private val value = CarPlayNowPlaying(title = "合成歌曲", playbackKnown = true, playing = true)
    private fun bridge(output: GalaxyDimMediaOutput = GalaxyDimMediaOutput { value, _, _, source ->
        assertEquals(13, source); sent.offer(value)
    }) = GalaxyDimMediaBridge(context, 13, { true }, { owner.get() }, events::offer,
        { mapOf("dimService" to 1, "dimEnabled" to 1, "dimExported" to 1, "dimPermissionGranted" to -1).mapValues { it.value.toLong() } },
        { output }, reconnectDelayMillis = 20)
    private fun eventContains(text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (events.poll(100, TimeUnit.MILLISECONDS)?.contains(text) == true) return
        }
        fail("未收到预期状态：$text")
    }

    @Test fun automaticallyBindsDeduplicatesUpdatesAndResumesAfterFocusReturns() {
        val bridge = bridge()
        try {
            eventContains("CONNECTED")
            bridge.update(value, null, "same")
            assertEquals(value, sent.poll(3, TimeUnit.SECONDS))
            repeat(20) { bridge.update(value, null, "same") }
            assertNull(sent.poll(300, TimeUnit.MILLISECONDS))
            val lyric = value.copy(title = "歌词下一句")
            bridge.update(lyric, null, "same")
            assertEquals(lyric, sent.poll(3, TimeUnit.SECONDS))
            owner.set("synthetic.other")
            bridge.wake()
            eventContains("WAITING_FOCUS")
            bridge.update(lyric.copy(elapsedMillis = 5000), null, "same")
            assertNull(sent.poll(300, TimeUnit.MILLISECONDS))
            owner.set(app.packageName)
            bridge.wake()
            assertEquals(5000L, sent.poll(3, TimeUnit.SECONDS)?.elapsedMillis)
            assertEquals(1, binds.get())
        } finally { bridge.close() }
        assertTrue(unbound.poll(3, TimeUnit.SECONDS) == true)
        bridge.update(value, null, "late")
        assertNull(sent.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun failedSendRetriesAndUnknownPhoneStateIsNotPublished() {
        val calls = AtomicInteger()
        val bridge = bridge(GalaxyDimMediaOutput { value, _, _, _ ->
            if (calls.incrementAndGet() == 1) throw IllegalStateException()
            sent.offer(value)
        })
        try {
            eventContains("CONNECTED")
            bridge.update(value.copy(playbackKnown = false), null, "same")
            assertNull(sent.poll(250, TimeUnit.MILLISECONDS))
            bridge.update(value, null, "same")
            assertEquals(value, sent.poll(3, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        } finally { bridge.close() }
    }

    @Test fun serviceDeathRebindsAndResendsOnceButLateOldConnectionCannotReplaceIt() {
        val bridge = bridge()
        try {
            eventContains("CONNECTED")
            bridge.update(value, null, "same")
            assertEquals(value, sent.poll(3, TimeUnit.SECONDS))
            val old = connected.get()
            old.onBindingDied(ComponentName(app, "synthetic"))
            assertEquals(value, sent.poll(3, TimeUnit.SECONDS))
            assertEquals(2, binds.get())
            old.onServiceConnected(ComponentName(app, "synthetic"), Binder())
            bridge.update(value, null, "same")
            assertNull(sent.poll(300, TimeUnit.MILLISECONDS))
        } finally { bridge.close() }
    }

    @Test fun bindingWithoutCallbackStopsAfterThreeAttemptsAndClosesBindings() {
        val stalled = object : ContextWrapper(context) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                binds.incrementAndGet()
                return true
            }
        }
        val bridge = GalaxyDimMediaBridge(stalled, 6, { true }, { app.packageName }, events::offer,
            { mapOf("dimService" to 1L, "dimEnabled" to 1L, "dimExported" to 1L) },
            { error("没有连接回调时不能加载契约") }, reconnectDelayMillis = 20)
        try {
            eventContains("BIND_RETRY_LIMIT")
            assertEquals(3, binds.get())
            assertEquals(3, unbound.size)
            assertTrue(sent.isEmpty())
        } finally { bridge.close() }
    }
}
