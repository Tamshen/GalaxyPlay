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

    @Test fun permanentLossRequiresExplicitResumeAndOldListenerCannotMuteNewRequest() {
        val track = track()
        val focus = AudioFocusCoordinator(context, true)
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focus.acquire(track, AudioChannel.MEDIA, attributes())
            val first = shadow.lastAudioFocusRequest
            first.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertSame(first, shadow.lastAudioFocusRequest)
            focus.resumeMedia()
            val second = shadow.lastAudioFocusRequest
            assertNotSame(first, second)
            first.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            focus.resumeMedia()
            assertSame(second, shadow.lastAudioFocusRequest)
        } finally { focus.close(); track.release() }
    }

    @Test fun upstreamKeepsMediaFocusWhilePhoneAndSiriTracksOverlap() {
        val media = track()
        val phone = track()
        val focus = AudioFocusCoordinator(context, true)
        try {
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            val request = shadow.lastAudioFocusRequest
            focus.acquire(phone, AudioChannel.PHONE, attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION))
            assertSame(request, shadow.lastAudioFocusRequest)
            assertEquals(1f, volume(media), 0f)
            assertEquals(1f, volume(phone), 0f)
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            focus.resumeMedia()
            assertSame(request, shadow.lastAudioFocusRequest)
            focus.release(phone)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN, shadow.lastAudioFocusRequest.durationHint)
            assertEquals(AudioAttributes.USAGE_MEDIA, shadow.lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
        } finally { focus.close(); media.release(); phone.release() }
    }

    @Test fun disabledFocusDoesNotAcquireOnPlaybackOrResume() {
        val track = track()
        val focus = AudioFocusCoordinator(context, false)
        try {
            focus.acquire(track, AudioChannel.MEDIA, attributes())
            focus.resumeMedia()
            assertNull(shadow.lastAudioFocusRequest)
        } finally { focus.close(); track.release() }
    }

    @Test fun navigationTakesNoFocusAndAssistantUsesMayDuck() {
        val assistant = track()
        val navigation = track()
        val focus = AudioFocusCoordinator(context, true)
        try {
            focus.acquire(navigation, AudioChannel.NAVIGATION, attributes(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
            assertNull(shadow.lastAudioFocusRequest)
            focus.acquire(assistant, AudioChannel.ASSISTANT, attributes(AudioAttributes.USAGE_ASSISTANT))
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, shadow.lastAudioFocusRequest.durationHint)
            assertEquals(AudioAttributes.USAGE_ASSISTANT, shadow.lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
            val request = shadow.lastAudioFocusRequest
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(1f, volume(navigation), 0f)
            assertEquals(0.2f, volume(assistant), 0f)
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(1f, volume(assistant), 0f)
            focus.release(assistant)
            assertSame(request, shadow.lastAudioFocusRequest)
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(1f, volume(assistant), 0f)
        } finally { focus.close(); assistant.release(); navigation.release() }
    }

    @Test fun denialAndLossDoNotSilenceSpeechOrReacquireAutomatically() {
        val assistant = track()
        val focus = AudioFocusCoordinator(context, true)
        try {
            shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            focus.acquire(assistant, AudioChannel.ASSISTANT, attributes(AudioAttributes.USAGE_ASSISTANT))
            assertEquals(1f, volume(assistant), 0f)
            val request = shadow.lastAudioFocusRequest
            for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                request.listener.onAudioFocusChange(loss)
                assertEquals(1f, volume(assistant), 0f)
                assertSame(request, shadow.lastAudioFocusRequest)
            }
        } finally { focus.close(); assistant.release() }
    }

    @Test fun phoneAloneUsesTransientAndLateCallbackAfterCloseCannotRestoreVolume() {
        val phone = track()
        val focus = AudioFocusCoordinator(context, true)
        try {
            focus.acquire(phone, AudioChannel.PHONE, attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION))
            val request = shadow.lastAudioFocusRequest
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.durationHint)
            focus.close()
            assertEquals(0f, volume(phone), 0f)
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0f, volume(phone), 0f)
        } finally { focus.close(); phone.release() }
    }

    @Test fun navigationDoesNotDuckMusicWhenFocusIsDisabled() {
        val media = track()
        val navigation = track()
        val focus = AudioFocusCoordinator(context, false)
        try {
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            focus.acquire(navigation, AudioChannel.NAVIGATION, attributes(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
            assertNull(shadow.lastAudioFocusRequest)
            assertEquals(1f, volume(media), 0f)
            assertEquals(1f, volume(navigation), 0f)
        } finally { focus.close(); media.release(); navigation.release() }
    }

    @Test fun assistantRoutedToMediaStillUsesAssistantFocusType() {
        val assistant = track()
        val focus = AudioFocusCoordinator(context, true)
        try {
            focus.acquire(assistant, AudioChannel.ASSISTANT, attributes(AudioAttributes.USAGE_MEDIA))
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, shadow.lastAudioFocusRequest.durationHint)
        } finally { focus.close(); assistant.release() }
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
