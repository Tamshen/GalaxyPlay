package com.shilapi.xcertplay

import android.app.Application
import android.net.Uri
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7ManualReportingTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val direct = Executor { it.run() }
    private val logs = mutableListOf<String>()
    private var current = true
    private var releases = 0
    @Before fun resetProviderPathCache() {
        // 每个 Robolectric 用例的应用目录独立，避免 AndroidX 静态路径缓存指向上一用例。
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(
            androidx.core.content.FileProvider::class.java, "sCache").clear()
    }
    private class Media : L7MediaCenterPort {
        val calls = mutableListOf<String>()
        val values = mutableListOf<CarPlayNowPlaying>()
        val covers = mutableListOf<Uri?>()
        lateinit var control: (Int) -> Boolean
        override fun initialize(ready: (Boolean) -> Unit, command: (Int) -> Boolean, focus: (String?) -> Unit, selected: (Int) -> Boolean) {
            control = command; calls.add("initialize"); ready(true)
        }
        override fun register(): Boolean { calls.add("register"); return true }
        override fun sources(values: IntArray): Boolean { calls.add("sources:${values.joinToString()}"); return true }
        override fun currentSource() { calls.add("current") }
        override fun requestPlay(): Boolean { calls.add("play"); return true }
        override fun focusClient(): String? = null
        override fun invalidate() { calls.add("invalidate") }
        override fun update(value: CarPlayNowPlaying, artwork: Uri?): Boolean { values.add(value); covers.add(artwork); return true }
        override fun progress(milliseconds: Long) { calls.add("progress:$milliseconds") }
        override fun unregister(): Boolean { calls.add("unregister"); return true }
        override fun close() { calls.add("close") }
    }
    private class Navigation : L7NavigationPort {
        val calls = mutableListOf<String>()
        var alive = true
        var failStop = false
        override fun initialize() { calls.add("initialize") }
        override fun ready() = alive
        override fun start() { calls.add("start") }
        override fun road(value: String) { calls.add("road:$value") }
        override fun stop() { calls.add("stop"); if (failStop) throw SecurityException("secret") }
        override fun invalidate() { calls.add("invalidate") }
        override fun close() { calls.add("unregister") }
    }

    @Test fun mediaUpdatesActualPortAndClearsBothSourcesAndRegistrationOnEnd() {
        val port = Media()
        val test = L7ManualMediaReport(app, logs::add, { releases++ }, { current }, port, direct)
        test.start()
        assertEquals(1, port.calls.count { it == "register" })
        assertFalse(port.values.last().playing)
        assertFalse(port.calls.contains("play"))
        val title = port.values.last().title
        test.action(L7ReportingAction.TRACK)
        assertNotEquals(title, port.values.last().title)
        test.action(L7ReportingAction.PLAY_PAUSE)
        assertTrue(port.values.last().playing)
        assertEquals(1, port.calls.count { it == "play" })
        test.action(L7ReportingAction.PROGRESS)
        assertTrue(port.calls.contains("progress:15000"))
        assertTrue(port.covers.any { it?.scheme == "content" })
        test.action(L7ReportingAction.COVER)
        assertNull(port.values.last().artworkTransferId)
        assertNull(port.covers.last())
        test.close(); test.close()
        assertEquals(listOf("invalidate", "sources:", "unregister", "close"), port.calls.takeLast(4))
        assertEquals(1, releases)
        val count = port.values.size
        test.action(L7ReportingAction.TRACK)
        assertEquals(count, port.values.size)
        assertFalse(port.control(CarPlayMediaButton.PLAY))
        assertTrue(logs.any { it.contains("androidMediaRelease") })
        assertFalse(logs.any { it.contains("userObservation") })
    }
    @Test fun missingMediaSdkStillPublishesNativeFixtureAndCanEnd() {
        val port = object : L7MediaCenterPort by Media() {
            override fun initialize(ready: (Boolean) -> Unit, command: (Int) -> Boolean, focus: (String?) -> Unit, selected: (Int) -> Boolean) {
                throw ClassNotFoundException("secret")
            }
        }
        val test = L7ManualMediaReport(app, logs::add, { releases++ }, { current }, port, direct)
        test.start(); test.action(L7ReportingAction.TRACK); test.close()
        assertEquals(1, releases)
        assertTrue(logs.any { it.contains("androidMedia result=RETURNED track=B") })
        assertTrue(logs.any { it.contains("exceptionType=ClassNotFoundException") })
        assertFalse(logs.any { it.contains("secret") })
    }
    @Test fun navigationWaitsForBinderThenStartsUpdatesAndStopsBeforeUnregister() {
        val port = Navigation().apply { alive = false }
        val test = L7ManualNavigationReport(app, logs::add, { releases++ }, { current }, port, direct)
        test.start()
        assertEquals(listOf("initialize"), port.calls)
        assertTrue(logs.any { it.contains("WAITING_BINDER") })
        port.alive = true
        test.action(L7ReportingAction.REFRESH)
        assertEquals("start", port.calls[1])
        val roadA = port.calls.last()
        test.action(L7ReportingAction.REFRESH)
        assertEquals(2, port.calls.count { it == roadA })
        assertEquals(1, port.calls.count { it == "start" })
        test.action(L7ReportingAction.ROAD)
        assertNotEquals(roadA, port.calls.last())
        val count = port.calls.size
        current = false; test.action(L7ReportingAction.ROAD)
        assertEquals(count, port.calls.size)
        test.close(); test.close()
        assertEquals(listOf("invalidate", "stop", "unregister"), port.calls.takeLast(3))
        assertEquals(1, releases)
        assertFalse(logs.any { it.contains("display=VERIFIED") })
    }
    @Test fun navigationStopFailureStillUnregistersAndRecordsOnlyErrorType() {
        val port = Navigation().apply { failStop = true }
        val test = L7ManualNavigationReport(app, logs::add, { releases++ }, { current }, port, direct)
        test.start(); test.close()
        assertEquals("unregister", port.calls.last())
        assertEquals(1, releases)
        assertTrue(logs.any { it.contains("exceptionType=SecurityException") })
        assertFalse(logs.any { it.contains("secret") })
    }
}
