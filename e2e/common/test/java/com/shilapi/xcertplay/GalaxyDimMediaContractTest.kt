package com.shilapi.xcertplay

import android.net.Uri
import android.os.Binder
import com.autolink.adapterbinder.IDimMediaInteractioncService
import com.autolink.adapterbinder.PlayMediaInfo
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyDimMediaContractTest {
    @Test fun delegatesOriginalSourceIdentityUriAndMillisecondsToFactoryContract() {
        var sent: PlayMediaInfo? = null
        var reserved = -1
        val receiver = object : IDimMediaInteractioncService.Stub() {
            override fun sendMessage(info: PlayMediaInfo, value: Int) { sent = info; reserved = value }
        }
        val adapter = GalaxyDimMediaContract(javaClass.classLoader!!, receiver)
        val cover = Uri.parse("file:///synthetic/cover.jpg")
        adapter.send(CarPlayNowPlaying(title = "文字", artist = "歌手", album = "专辑", durationMillis = 300000,
            elapsedMillis = 12500, playbackKnown = true, playing = true), cover, "session:9", 13)
        val value = requireNotNull(sent)
        assertEquals(13, value.source_type)
        assertEquals("session:9", value.uuid)
        assertEquals("文字", value.title)
        assertEquals("歌手", value.artist)
        assertEquals("专辑", value.album)
        assertEquals(cover, value.artworkUrl)
        assertEquals(300000L, value.duration)
        assertEquals(12500L, value.current_progress)
        assertEquals(1, value.playback_status)
        assertEquals(0, reserved)
        adapter.send(CarPlayNowPlaying(title = "下一句", playbackKnown = true), null, "session:9", 6)
        assertEquals(6, sent!!.source_type)
        assertEquals(0, sent!!.playback_status)
        assertNull(sent!!.artworkUrl)
    }

    @Test fun incompatibleBinderIsRejectedBeforeSend() {
        assertThrows(IllegalStateException::class.java) { GalaxyDimMediaContract(javaClass.classLoader!!, Binder()) }
    }
}
