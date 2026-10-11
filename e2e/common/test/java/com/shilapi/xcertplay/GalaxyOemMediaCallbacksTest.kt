package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyOemMediaCallbacksTest {
    class Client : GalaxyOemMediaContractTest.Client() {
        fun onSourceChanged(source: Int, previous: String?): Boolean = false
    }
    private var active = true
    private val commands = mutableListOf<Int>()
    private val selections = mutableListOf<Int>()
    private val focuses = mutableListOf<String?>()
    private val info = GalaxyOemMediaContractTest.Info()
    private fun callback(mode: GalaxyMediaReportingChannel.Mode, source: Int = 6) = GalaxyOemMediaCallbacks(
        mode, source, { active }, { CarPlayNowPlaying(elapsedMillis = 32123) }, { info },
        { commands.add(it); true }, { focuses.add(it) }, { selections.add(it); it == source })
    private fun call(handler: GalaxyOemMediaCallbacks, name: String, vararg args: Any?): Any? =
        handler.invoke(null, Client::class.java.methods.single { it.name == name }, args)

    @Test fun compatibilitySourceNotificationsAreAcknowledgedWithoutPlayingOrSelectingAgain() {
        for (mode in GalaxyMediaReportingChannel.Mode.entries) {
            assertEquals(mode != GalaxyMediaReportingChannel.Mode.CURRENT,
                call(callback(mode), "onSourceChanged", 6, "other"))
        }
        assertTrue(commands.isEmpty())
        assertTrue(selections.isEmpty())
    }

    @Test fun bothOnlineAndLocalClientsExposeConsistentSourceProgressAndSnapshot() {
        for (mode in GalaxyMediaReportingChannel.Mode.entries) {
            val source = if (mode == GalaxyMediaReportingChannel.Mode.LOCAL) 0 else 6
            val handler = callback(mode, source)
            assertEquals(source, call(handler, "getCurrentSourceType"))
            assertArrayEquals(intArrayOf(source), call(handler, "getMediaSourceTypeList") as IntArray)
            assertEquals(32123L, call(handler, "getCurrentProgress"))
            assertSame(info, call(handler, "getMusicPlaybackInfo"))
            assertTrue(call(handler, "onNext") as Boolean)
            assertTrue(call(handler, "onSourceSelected", source) as Boolean)
            call(handler, "onMediaCenterFocusChanged", "own")
        }
        assertEquals(List(4) { CarPlayMediaButton.NEXT }, commands)
        assertEquals(listOf(6, 6, 6, 0), selections)
        assertEquals(List(4) { "own" }, focuses)
    }

    @Test fun staleClientCannotAcknowledgeNotifyOrReadAnyCurrentSession() {
        for (mode in GalaxyMediaReportingChannel.Mode.entries) {
            val handler = callback(mode)
            active = false
            for (name in listOf("onPlay", "onPause", "onNext", "onPrevious")) assertEquals(false, call(handler, name))
            assertEquals(false, call(handler, "onSourceSelected", 6))
            assertEquals(false, call(handler, "onSourceChanged", 6, "other"))
            call(handler, "onMediaCenterFocusChanged", "own")
            assertEquals(-1, call(handler, "getCurrentSourceType"))
            assertArrayEquals(intArrayOf(), call(handler, "getMediaSourceTypeList") as IntArray)
            assertEquals(0L, call(handler, "getCurrentProgress"))
            assertNull(call(handler, "getMusicPlaybackInfo"))
        }
        assertTrue(commands.isEmpty()); assertTrue(selections.isEmpty()); assertTrue(focuses.isEmpty())
    }
}
