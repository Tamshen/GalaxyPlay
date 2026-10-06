package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.content.Intent
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GalaxyMediaCallbackTest {
    private val sent = mutableListOf<Int>()
    private val callback = GalaxyMediaCallback { index, _ -> sent += index }

    @Test
    fun controllerPlayAndPauseAreExplicit() {
        callback.onPlay()
        callback.onPause()
        callback.onSkipToNext()
        callback.onSkipToPrevious()

        assertEquals(
            listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS),
            sent,
        )
    }

    @Test
    fun hardwarePlayAndPauseKeysToggle() {
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        press(CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE)

        assertEquals(List(3) { CarPlayMediaButton.PLAY_PAUSE }, sent)
    }

    @Test fun experimentalPlayPauseKeyNeedsOptInAndStopsAfterDisable() {
        var enabled = false
        val experimental = CarPlayMediaCallback(experimentalDiLink3Keys = { enabled }) { index, _ -> sent += index }
        val key = button(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, 331, 0))
        experimental.onMediaButtonEvent(key)
        assertEquals(emptyList<Int>(), sent)
        enabled = true
        experimental.onMediaButtonEvent(key)
        assertEquals(listOf(CarPlayMediaButton.PLAY_PAUSE), sent)
        enabled = false
        experimental.onMediaButtonEvent(key)
        assertEquals(listOf(CarPlayMediaButton.PLAY_PAUSE), sent)
    }

    @Test
    fun aHeldKeySendsOnePress() {
        press(KeyEvent.KEYCODE_MEDIA_NEXT, repeat = 1)
        callback.onMediaButtonEvent(button(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0)))

        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
    }

    @Test fun l7ExplicitHardwarePauseDoesNotToggleMusicBackOn() {
        val l7 = GalaxyMediaCallback(explicitHardwareActions = true) { index, _ -> sent += index }
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).forEach {
            l7.onMediaButtonEvent(button(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, it, 0)))
        }
        assertEquals(listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.PLAY_PAUSE), sent)
    }

    @Test fun tracedKeyDistinguishesRepeatReleaseAndUnsupportedWithoutForwarding() {
        val store = L7SteeringDiagnostics.store
        store.clear()
        val received = mutableListOf<L7SteeringTrace>()
        val traced = GalaxyMediaCallback(explicitHardwareActions = true,
            tracedSend = { _, _, ticket -> received += ticket }) { _, _ -> error("不得绕过诊断入口") }
        traced.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0), "window-key")
        traced.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 1), "window-key")
        traced.onKey(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0), "window-key")
        traced.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP, 0), "media-session-key")
        assertEquals(1, received.size)
        assertEquals(listOf("REPEAT", "KEY_UP_OR_OTHER_ACTION", "UNSUPPORTED_KEY"),
            store.snapshot().events.filter { it.stage in listOf("FILTER", "DROP") }.map { it.detail })
    }

    @Test
    fun aPendingArtworkTransferKeepsThePreviousArt() {
        val previous = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cached = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        assertSame(previous, CarPlayMediaKeys.nextArtwork(7, emptyMap(), previous))
        assertSame(cached, CarPlayMediaKeys.nextArtwork(7, mapOf(7 to cached), previous))
        assertNull(CarPlayMediaKeys.nextArtwork(7, mapOf(7 to null), previous))
        assertNull(CarPlayMediaKeys.nextArtwork(null, mapOf(7 to cached), previous))
    }

    @Test
    fun thePlaceholderRastersAtArtworkSize() {
        val placeholder = CarPlayMediaKeys.placeholderArt(RuntimeEnvironment.getApplication())

        assertEquals(384, placeholder?.width)
        assertEquals(384, placeholder?.height)
    }

    private fun press(keyCode: Int, repeat: Int = 0) {
        for (count in 0..repeat) {
            callback.onMediaButtonEvent(button(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, count)))
        }
    }

    private fun button(event: KeyEvent) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)
}
