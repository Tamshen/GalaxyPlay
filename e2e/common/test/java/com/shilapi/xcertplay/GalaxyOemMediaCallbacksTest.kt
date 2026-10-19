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
    private fun callback(source: Int = 6) = GalaxyOemMediaCallbacks(
        source, { active }, { CarPlayNowPlaying(elapsedMillis = 32123) }, { info },
        { commands.add(it); true }, { focuses.add(it) }, { selections.add(it); it == source })
    private fun call(handler: GalaxyOemMediaCallbacks, name: String, vararg args: Any?): Any? =
        handler.invoke(null, Client::class.java.methods.single { it.name == name }, args)

    @Test fun sourceNotificationsDoNotClaimHandlingOrPlayAgain() {
        assertEquals(false, call(callback(), "onSourceChanged", 6, "other"))
        assertTrue(commands.isEmpty())
        assertTrue(selections.isEmpty())
    }

    @Test fun clientExposesConsistentSourceProgressAndSnapshot() {
        val handler = callback()
        assertEquals(6, call(handler, "getCurrentSourceType"))
        assertArrayEquals(intArrayOf(6), call(handler, "getMediaSourceTypeList") as IntArray)
        assertEquals(32123L, call(handler, "getCurrentProgress"))
        assertSame(info, call(handler, "getMusicPlaybackInfo"))
        assertTrue(call(handler, "onNext") as Boolean)
        assertTrue(call(handler, "onSourceSelected", 6) as Boolean)
        call(handler, "onMediaCenterFocusChanged", "own")
        assertEquals(listOf(CarPlayMediaButton.NEXT), commands)
        assertEquals(listOf(6), selections)
        assertEquals(listOf("own"), focuses)
    }

    @Test fun staleClientCannotAcknowledgeNotifyOrReadAnyCurrentSession() {
        val handler = callback()
        active = false
        for (name in listOf("onPlay", "onPause", "onNext", "onPrevious")) assertEquals(false, call(handler, name))
        assertEquals(false, call(handler, "onSourceSelected", 6))
        assertEquals(false, call(handler, "onSourceChanged", 6, "other"))
        call(handler, "onMediaCenterFocusChanged", "own")
        assertEquals(-1, call(handler, "getCurrentSourceType"))
        assertArrayEquals(intArrayOf(), call(handler, "getMediaSourceTypeList") as IntArray)
        assertEquals(0L, call(handler, "getCurrentProgress"))
        assertNull(call(handler, "getMusicPlaybackInfo"))
        assertTrue(commands.isEmpty()); assertTrue(selections.isEmpty()); assertTrue(focuses.isEmpty())
    }
}
