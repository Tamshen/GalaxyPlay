package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers

/** Exercises the real sink/uplink, including creation, teardown and exported diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE, shadows = [MicrophoneSourceFallbackTest.RecorderBuilder::class])
class MicrophoneSourceFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = AudioStreamId(100, "speechrecognition")
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val captured = AtomicReference<AudioRecord?>()
    private val captureStarted = CountDownLatch(1)
    private lateinit var sink: AndroidMediaSink

    @Before fun setUp() {
        RecorderBuilder.attempts.clear()
        RecorderBuilder.built.clear()
        RecorderBuilder.rejectSources = emptySet()
        RecorderBuilder.uninitializedSources = emptySet()
        RecorderBuilder.startRejectedSources = emptySet()
        RecorderBuilder.beforeBuild = {}
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_RINGTONE
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add)
        ShadowAudioRecord.setSourceProvider { recorder ->
            captured.set(recorder)
            captureStarted.countDown()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        sink.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun siriCreationFailureRetriesCommunicationOnceWithoutCallEffectsOrModeChanges() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        val recorder = requireNotNull(captured.get())
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, recorder.audioSource)
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertTrue(diagnostics.any { it.contains("failure type=speechrecognition source=VOICE_RECOGNITION") && it.contains("stage=RECORDER_CREATION") })
        assertTrue(diagnostics.any { it.startsWith("Microphone: start type=speechrecognition source=VOICE_COMMUNICATION") })
        sink.onMicrophoneStopped(id)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        assertTrue(diagnostics.any { it.contains("stats type=speechrecognition source=VOICE_COMMUNICATION") && it.endsWith("ended=true") })
    }

    @Test fun siriInitializationFailureReleasesRejectedRecorderBeforeFallbackCapture() {
        RecorderBuilder.uninitializedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(2, RecorderBuilder.built.size)
        assertTrue((RecorderBuilder.built.first() as RejectedRecorder).released)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, RecorderBuilder.built.last().recordingState)
        assertTrue(diagnostics.any { it.contains("source=VOICE_RECOGNITION") && it.contains("stage=RECORDER_INITIALIZATION") })
    }

    @Test fun siriStartFailureReleasesRecorderBeforeCommunicationFallback() {
        RecorderBuilder.startRejectedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertTrue((RecorderBuilder.built.first() as StartRejectedRecorder).released)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, requireNotNull(captured.get()).audioSource)
        assertTrue(diagnostics.any { it.contains("source=VOICE_RECOGNITION") && it.contains("stage=RECORDING") })
    }

    @Test fun cancellationDuringCreationCannotPublishOrRetryARecorder() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        RecorderBuilder.beforeBuild = { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) }
        val uplink = MicrophoneUplink(config("speechrecognition"))
        var result = true
        val starter = Thread { result = uplink.start() }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = Thread { uplink.close() }.apply { start() }
        val closed = ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(uplink, "closed")
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!closed.get() && System.nanoTime() < until) Thread.yield()
        assertTrue(closed.get())
        resume.countDown()
        starter.join(5_000); closer.join(5_000)
        assertFalse(starter.isAlive); assertFalse(closer.isAlive)
        assertFalse(result)
        assertEquals(1, RecorderBuilder.attempts.size)
        assertTrue(RecorderBuilder.built.all { it.state == AudioRecord.STATE_UNINITIALIZED })
        assertNull(captured.get())
    }

    @Test fun successfulOriginalSourceDoesNotCreateFallback() {
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION), RecorderBuilder.attempts)
    }

    @Test fun factorySourceIsTriedFirstAndCloseIsPermanent() {
        val uplink = MicrophoneUplink(config("speechrecognition"), factorySource = MediaRecorder.AudioSource.MIC)
        assertTrue(uplink.start())
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(MediaRecorder.AudioSource.MIC), RecorderBuilder.attempts)
        uplink.close()
        assertFalse(uplink.start())
        assertEquals(AudioRecord.STATE_UNINITIALIZED, requireNotNull(captured.get()).state)
    }

    @Test fun bothUnsupportedSourcesFailOnceAndALaterStreamCanStart() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertNull(captured.get())
        assertEquals(2, diagnostics.count { it.contains("stage=RECORDER_CREATION") })
        assertTrue(diagnostics.none { it.startsWith("Microphone: start") })

        RecorderBuilder.rejectSources = emptySet()
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, requireNotNull(captured.get()).audioSource)
    }

    @Test fun telephonyCreationFailureDoesNotRetryOrLeaveTheCallModeActive() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(AudioStreamId(101, "telephony"), config("telephony"))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertNull(captured.get())
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    private fun config(type: String) = MicrophoneConfig(audioType = type, sampleRate = 16_000,
        channels = 1, payloadType = 100, frameMillis = 20, host = InetAddress.getLoopbackAddress(),
        port = 9, key = ByteArray(32))

    @Implements(AudioRecord.Builder::class)
    class RecorderBuilder {
        @RealObject private lateinit var builder: AudioRecord.Builder

        @Implementation fun build(): AudioRecord {
            val attributes = ReflectionHelpers.getField<AudioAttributes>(builder, "mAttributes")
            val source = ReflectionHelpers.callInstanceMethod<Int>(attributes, "getCapturePreset")
            attempts += source
            beforeBuild()
            if (source in rejectSources) throw UnsupportedOperationException("Unsupported test capture source")
            return (if (source in uninitializedSources) RejectedRecorder(source)
                else if (source in startRejectedSources) StartRejectedRecorder(source)
                else Shadow.directlyOn<AudioRecord, AudioRecord.Builder>(builder, AudioRecord.Builder::class.java, "build")).also {
                built += it
            }
        }

        companion object {
            val attempts = CopyOnWriteArrayList<Int>()
            val built = CopyOnWriteArrayList<AudioRecord>()
            var beforeBuild: () -> Unit = {}
            var rejectSources = emptySet<Int>()
            var uninitializedSources = emptySet<Int>()
            var startRejectedSources = emptySet<Int>()
        }
    }

    class StartRejectedRecorder(source: Int) : AudioRecord(source, 16_000,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096) {
        var released = false
        override fun startRecording() { throw IllegalStateException("source refused start") }
        override fun release() { released = true; super.release() }
    }

    class RejectedRecorder(source: Int) : AudioRecord(source, 16_000,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096) {
        var released = false
            private set
        override fun getState(): Int = STATE_UNINITIALIZED
        override fun release() {
            released = true
            super.release()
        }
    }
}
