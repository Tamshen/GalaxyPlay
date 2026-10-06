package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Looper
import android.os.Message
import android.os.Process
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyCodecProbeClientTest {
    private val context = mock(Context::class.java)
    private val results = mutableListOf<CodecProbeResult>()
    private val stages = mutableListOf<CodecProbeStage>()
    private lateinit var client: GalaxyCodecProbeClient
    @Before fun setup() {
        `when`(context.packageName).thenReturn("test.codec")
        `when`(context.bindService(any(Intent::class.java), any(ServiceConnection::class.java), eq(Context.BIND_AUTO_CREATE))).thenReturn(true)
        client = GalaxyCodecProbeClient(context, 12, CodecProbeMethod.NDK, CodecProbeVideo.HEVC,
            "hardware", false, mock(Surface::class.java), stages::add, results::add)
        client.start()
    }
    @After fun cleanup() { client.close(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20)) }
    private fun receive(what: Int, uid: Int = Process.myUid(), data: Bundle) {
        val message = Message.obtain().apply { this.what = what; sendingUid = uid; this.data = data }
        ReflectionHelpers.callInstanceMethod<Unit>(client, "receive", ClassParameter.from(Message::class.java, message))
    }
    @Test fun unresponsiveBindingTimesOutAndCompletesOnlyOnce() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7999)); assertTrue(results.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals("TIMEOUT", results.single().reason); assertFalse(results.single().processExited); assertFalse(results.single().workerStarted)
        assertEquals(CodecProbeVideo.HEVC, results.single().video)
        client.stop("CANCELLED"); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(1, results.size)
        verify(context, times(1)).unbindService(any(ServiceConnection::class.java))
    }
    @Test fun wrongUidAndWrongRunCannotMoveTheWatchdogOrPublishResults() {
        val data = Bundle().apply { putLong("run", 12); putInt("stage", CodecProbeStage.FEED.ordinal) }
        receive(CodecProbeProtocol.STAGE, Process.myUid()+1, data)
        data.putLong("run", 99); receive(CodecProbeProtocol.STAGE, data=data)
        assertTrue(stages.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals("TIMEOUT", results.single().reason)
    }
    @Test fun helloCannotAuthorizeEndingTheMainProcess() {
        receive(CodecProbeProtocol.HELLO, data=Bundle().apply { putInt("uid", Process.myUid()); putInt("pid", Process.myPid()) })
        assertEquals("INVALID_PROCESS", results.single().reason)
        assertFalse(results.single().processExited)
    }
    @Test fun progressCannotExtendTheOverallDeadline() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            receive(CodecProbeProtocol.STAGE, data=Bundle().apply { putLong("run", 12); putInt("stage", CodecProbeStage.FEED.ordinal) })
        }
        assertTrue(results.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals("TIMEOUT", results.single().reason)
    }
    @Test fun cancelledRunRejectsLateSuccessfulResult() {
        client.stop("BACKGROUND")
        val success = CodecProbeResult(12, CodecProbeMethod.NDK, CodecProbeStage.DONE,
            hardware=true, outputs=60, eos=true, released=true)
        receive(CodecProbeProtocol.RESULT, data=success.bundle())
        assertEquals("BACKGROUND", results.single().reason)
        assertFalse(results.single().hardwarePassed)
    }
}
