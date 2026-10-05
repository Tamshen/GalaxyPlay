package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE, shadows = [AudioFocusCoordinatorTest.VolumeTrack::class])
class AudioFocusCoordinatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val shadow = shadowOf(audio)

    @Test fun l6NavigationUsesMayDuckAndReleaseRestoresMediaWithoutChangingL7Defaults() {
        val json = org.json.JSONObject(AudioRoutingTemplate.system().toJson())
            .put("focusGains", org.json.JSONObject().put("navigation", 3).put("ringtone", 3).put("phone", 2))
        val media = track(); val navigation = track(); val phone = track()
        val focus = AudioFocusCoordinator(context, true, factoryRouting = true,
            template = AudioRoutingTemplate.parse(json.toString()))
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            assertEquals(AudioManager.AUDIOFOCUS_GAIN, shadow.lastAudioFocusRequest.durationHint)
            focus.acquire(navigation, AudioChannel.NAVIGATION, attributes(12))
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, shadow.lastAudioFocusRequest.durationHint)
            assertEquals(0.2f, volume(media), 0f)
            focus.acquire(phone, AudioChannel.PHONE, attributes(2))
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadow.lastAudioFocusRequest.durationHint)
            focus.release(phone); focus.release(navigation)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN, shadow.lastAudioFocusRequest.durationHint)
            assertEquals(1f, volume(media), 0f)
        } finally { focus.close(); media.release(); navigation.release(); phone.release() }
    }

    @Test fun diagnosticSnapshotObservesPhoneReleaseWithoutRequestingFocusOrChangingVolumes() {
        val media = track(); val phone = track()
        val focus = AudioFocusCoordinator(context, true, factoryRouting = true)
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            focus.acquire(phone, AudioChannel.PHONE, attributes(2))
            val request = shadow.lastAudioFocusRequest
            assertTrue(focus.diagnosticState().contains("focusChannel=PHONE"))
            assertSame(request, shadow.lastAudioFocusRequest)
            assertEquals(0f, volume(media), 0f)
            focus.release(phone)
            val recovered = shadow.lastAudioFocusRequest
            assertTrue(focus.diagnosticState().contains("focusChannel=MEDIA focusHeld=true"))
            assertSame(recovered, shadow.lastAudioFocusRequest)
            assertEquals(1f, volume(media), 0f)
        } finally { focus.close(); media.release(); phone.release() }
    }

    @Test fun navigationDucksOnlyMediaAndReleaseRestoresIt() {
        val media = track(); val navigation = track()
        val focus = AudioFocusCoordinator(context, true, factoryRouting = true)
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            focus.acquire(navigation, AudioChannel.NAVIGATION, attributes(12))
            val request = shadow.lastAudioFocusRequest
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.durationHint)
            assertEquals(12, request.audioFocusRequest.audioAttributes.usage)
            assertEquals(0.2f, volume(media), 0f)
            assertEquals(1f, volume(navigation), 0f)
            focus.release(navigation)
            assertEquals(1f, volume(media), 0f)
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(1f, volume(media), 0f)
        } finally { focus.close(); media.release(); navigation.release() }
    }

    @Test fun phoneThenSiriThenNavigationTakePriorityOverMedia() {
        val tracks = List(4) { track() }
        val focus = AudioFocusCoordinator(context, true, factoryRouting = true)
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            listOf(AudioChannel.MEDIA, AudioChannel.NAVIGATION, AudioChannel.ASSISTANT, AudioChannel.PHONE)
                .zip(listOf(1, 12, 16, 2)).forEachIndexed { index, (channel, usage) ->
                    focus.acquire(tracks[index], channel, attributes(usage))
                    assertEquals(usage, shadow.lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
                }
            assertEquals(0f, volume(tracks[0]), 0f)
            assertEquals(0f, volume(tracks[2]), 0f)
            assertEquals(1f, volume(tracks[3]), 0f)
            focus.release(tracks[3])
            assertEquals(16, shadow.lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
            assertEquals(1f, volume(tracks[2]), 0f)
        } finally { focus.close(); tracks.forEach { it.release() } }
    }

    @Test fun lossDoesNotReacquireUntilExplicitPlaybackAndOldListenerIsIgnored() {
        val media = track()
        val focus = AudioFocusCoordinator(context, true, factoryRouting = true)
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            val first = shadow.lastAudioFocusRequest
            first.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertSame(first, shadow.lastAudioFocusRequest)
            assertEquals(0f, volume(media), 0f)
            focus.resumeMedia()
            assertEquals(1f, volume(media), 0f)
            val current = shadow.lastAudioFocusRequest
            focus.close()
            current.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0f, volume(media), 0f)
        } finally { focus.close(); media.release() }
    }

    @Test fun deniedFactoryFocusSilencesTrackWithoutAutomaticRequestLoop() {
        val assistant = track()
        val focus = AudioFocusCoordinator(context, true, factoryRouting = true)
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            focus.acquire(assistant, AudioChannel.ASSISTANT, attributes(16))
            assertEquals(0f, volume(assistant), 0f)
            val request = shadow.lastAudioFocusRequest
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertSame(request, shadow.lastAudioFocusRequest)
        } finally { focus.close(); assistant.release() }
    }

    @Test fun disabledFocusLeavesAllTracksAndRequestsAlone() {
        val media = track(); val navigation = track()
        val focus = AudioFocusCoordinator(context, false, factoryRouting = true)
        try {
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            focus.acquire(navigation, AudioChannel.NAVIGATION, attributes(12))
            focus.resumeMedia()
            assertNull(shadow.lastAudioFocusRequest)
            assertEquals(1f, volume(media), 0f)
            assertEquals(1f, volume(navigation), 0f)
        } finally { focus.close(); media.release(); navigation.release() }
    }

    @Test fun systemDuckAndGainPreserveLocalNavigationMix() {
        val media = track(); val navigation = track()
        val focus = AudioFocusCoordinator(context, true, factoryRouting = true)
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            focus.acquire(navigation, AudioChannel.NAVIGATION, attributes(12))
            val request = shadow.lastAudioFocusRequest
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(0.04f, volume(media), 0.0001f)
            assertEquals(0.2f, volume(navigation), 0f)
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0.2f, volume(media), 0f)
            assertEquals(1f, volume(navigation), 0f)
        } finally { focus.close(); media.release(); navigation.release() }
    }

    @Test fun ringtoneGetsTransientFocusInsteadOfNavigationDucking() {
        val ringtone = track()
        val focus = AudioFocusCoordinator(context, true, factoryRouting = true)
        try {
            focus.acquire(ringtone, AudioChannel.RINGTONE, attributes(6))
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadow.lastAudioFocusRequest.durationHint)
            assertEquals(6, shadow.lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
        } finally { focus.close(); ringtone.release() }
    }

    @Test fun genericProfileStillSkipsNavigationAndDoesNotMuteRejectedSpeech() {
        val navigation = track(); val assistant = track()
        val focus = AudioFocusCoordinator(context, true, factoryRouting = false)
        try {
            focus.acquire(navigation, AudioChannel.NAVIGATION, attributes(12))
            assertNull(shadow.lastAudioFocusRequest)
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            focus.acquire(assistant, AudioChannel.ASSISTANT, attributes(16))
            assertEquals(1f, volume(assistant), 0f)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, shadow.lastAudioFocusRequest.durationHint)
        } finally { focus.close(); navigation.release(); assistant.release() }
    }

    private fun volume(track: AudioTrack) = Shadow.extract<VolumeTrack>(track).outputVolume

    // 观察实际传给 AudioTrack 的音量，避免只验证焦点日志而漏掉静音回归。
    @Implements(AudioTrack::class)
    class VolumeTrack : ShadowAudioTrack() {
        var outputVolume = 1f
        @Implementation fun setVolume(value: Float): Int {
            outputVolume = value
            return AudioTrack.SUCCESS
        }
    }

    private fun attributes(usage: Int = AudioAttributes.USAGE_MEDIA) = AudioAttributes.Builder()
        .setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private fun track() = AudioTrack.Builder().setAudioAttributes(attributes())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(4096).build()
}
