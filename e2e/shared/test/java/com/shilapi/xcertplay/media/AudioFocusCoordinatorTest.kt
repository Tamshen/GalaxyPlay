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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
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

    @Test fun mediaResumeCannotStealPhoneFocusAndPhoneEndRestoresMedia() {
        val media = track()
        val phone = track()
        val focus = AudioFocusCoordinator(context, true)
        try {
            focus.acquire(media, AudioChannel.MEDIA, attributes())
            focus.acquire(phone, AudioChannel.PHONE, attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION))
            val request = shadow.lastAudioFocusRequest
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.durationHint)
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

    @Test fun assistantUsesTransientFocusWhileNavigationCanDuck() {
        val assistant = track()
        val navigation = track()
        val focus = AudioFocusCoordinator(context, true)
        try {
            focus.acquire(navigation, AudioChannel.NAVIGATION, attributes(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, shadow.lastAudioFocusRequest.durationHint)
            focus.acquire(assistant, AudioChannel.ASSISTANT, attributes(AudioAttributes.USAGE_ASSISTANT))
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadow.lastAudioFocusRequest.durationHint)
            assertEquals(AudioAttributes.USAGE_ASSISTANT, shadow.lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
            focus.release(assistant)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, shadow.lastAudioFocusRequest.durationHint)
        } finally { focus.close(); assistant.release(); navigation.release() }
    }

    private fun attributes(usage: Int = AudioAttributes.USAGE_MEDIA) = AudioAttributes.Builder()
        .setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private fun track() = AudioTrack.Builder().setAudioAttributes(attributes())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(4096).build()
}
