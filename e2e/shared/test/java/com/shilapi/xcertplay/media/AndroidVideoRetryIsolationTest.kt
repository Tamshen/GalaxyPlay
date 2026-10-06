package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AndroidVideoRetryIsolationTest {
    @Test fun retryCompletesOnVideoWorkerWithoutClosingAudioOrMicrophone() {
        val sink = AndroidMediaSink()
        val audio = mock(Class.forName("com.shilapi.xcertplay.media.AudioRenderer"))
        val microphone = mock(Class.forName("com.shilapi.xcertplay.media.MicrophoneUplink"))
        val id = AudioStreamId(100, "telephony")
        val audioMap = channels(sink, "audioRenderers")
        val microphoneMap = channels(sink, "microphoneUplinks")
        audioMap[id] = audio
        microphoneMap[id] = microphone
        try {
            assertFalse(sink.retryMainVideo())
            sink.onVideoFrame(110, byteArrayOf(1))
            assertTrue(sink.retryMainVideo())
            @Suppress("UNCHECKED_CAST")
            val decoders = field(sink, "videoDecoders") as Map<Int, Any>
            val worker = decoders.getValue(110)
            val queue = worker.javaClass.getDeclaredField("queue").apply { isAccessible = true }
                .get(worker) as VideoDecodeQueue
            val applied = CountDownLatch(1)
            queue.offer(VideoJob.SurfaceChanged(null) { applied.countDown() })
            assertTrue(applied.await(2, TimeUnit.SECONDS))
            assertSame(audio, audioMap[id])
            assertSame(microphone, microphoneMap[id])
            verifyNoInteractions(audio, microphone)
            assertTrue(sink.hasMicrophoneUplink())
        } finally { sink.close() }
        assertFalse(sink.retryMainVideo())
        assertFalse(sink.hasMicrophoneUplink())
    }

    @Suppress("UNCHECKED_CAST")
    private fun channels(sink: AndroidMediaSink, name: String) = field(sink, name) as MutableMap<AudioStreamId, Any>
    private fun field(sink: AndroidMediaSink, name: String) = sink.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(sink)
}
