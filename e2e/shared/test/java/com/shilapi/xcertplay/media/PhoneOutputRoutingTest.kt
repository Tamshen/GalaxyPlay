package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class PhoneOutputRoutingTest {
    @Test fun rendererUsesSavedPhoneRouteButStillStartsAndReleasesCallResources() {
        for (factory in listOf(false, true)) for (choice in listOf(101, 103, 104)) {
            val ready = CountDownLatch(1)
            val released = CountDownLatch(1)
            val logs = CopyOnWriteArrayList<String>()
            val sink = AndroidMediaSink(platformAdaptation = GalaxyMediaPolicy(factory, false,
                AudioRoutingTemplate.system().withChoice(AudioOutputRole.PHONE, choice)),
                onAudioDiagnostic = { line ->
                    logs.add(line)
                    if (line.startsWith("Audio: ready")) ready.countDown()
                    if (line.contains("stage=RELEASED")) released.countDown()
                })
            val id = AudioStreamId(100, "telephony")
            try {
                sink.onAudioStarted(id, AudioFormat(AudioCodecKind.LPCM, 16_000, 1, 100, "telephony"), 0)
                assertTrue(logs.toString(), ready.await(3, TimeUnit.SECONDS))
                val usage = AudioOutputPolicy.usage(AudioOutputRole.PHONE, choice)
                assertTrue(logs.toString(), logs.any { it.startsWith("Audio: ready") && it.contains("usage=$usage ") })
                assertTrue(logs.toString(), logs.any { it.contains("stage=STARTED") && it.contains("DOWNLINK") })
                sink.onAudioStopped(id)
                assertTrue(logs.toString(), released.await(3, TimeUnit.SECONDS))
            } finally { sink.close() }
        }
    }
}
