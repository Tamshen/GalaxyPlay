package com.shilapi.xcertplay

import android.os.Looper
import android.view.View
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostVideoRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var sink: AndroidMediaSink
    private lateinit var panel: L7VideoRecoveryPanel

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        sink = mock(AndroidMediaSink::class.java)
        panel = L7VideoRecoveryPanel(activity, retry = { false }, settings = {})
        set("sink", sink)
        set("videoRecoveryPanel", panel)
        set("restartGeneration", 7)
        owner(activity)
    }

    @After fun tearDown() {
        (get("shuttingDown") as AtomicBoolean).set(true)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        owner(null)
    }

    @Test fun h264FailureIsVisibleAndRetryTouchesOnlyTheVideoSink() {
        failVideo(7)
        assertEquals(7 to VideoCodec.H264, get("pendingVideoFailure"))
        assertEquals(View.VISIBLE, panel.visibility)
        assertNull(get("videoFailureDialog"))
        `when`(sink.retryMainVideo()).thenReturn(true)
        clearInvocations(sink)
        assertTrue(retry())
        verify(sink).retryMainVideo()
        verifyNoMoreInteractions(sink)
        assertEquals(7, get("restartGeneration"))
        assertEquals(View.VISIBLE, panel.visibility)
    }

    @Test fun onlyActualCurrentRecoveryHidesThePanel() {
        failVideo(7)
        recover(6)
        assertEquals(View.VISIBLE, panel.visibility)
        recover(7)
        assertEquals(View.VISIBLE, panel.visibility) // 新 worker 仍失败，迟到的恢复不能清除。
        `when`(sink.currentVideoFailure()).thenReturn(null)
        recover(7)
        assertEquals(View.GONE, panel.visibility)
        assertNull(get("pendingVideoFailure"))
        assertFalse(retry())
    }

    @Test fun retiredStreamFailureCannotReappearAfterNewStreamAlreadyRecovered() {
        `when`(sink.currentVideoFailure()).thenReturn(null)
        invokeFailure(7)
        assertEquals(View.GONE, panel.visibility)
        assertNull(get("pendingVideoFailure"))
    }

    @Test fun callbacksQueuedBeforeRestartAreRejectedWhenTheUiExecutesThem() {
        `when`(sink.currentVideoFailure()).thenReturn(VideoCodec.H264 to "test failure")
        val thread = Thread { invokeFailure(7) }
        thread.start()
        thread.join(1000)
        assertFalse(thread.isAlive)
        set("restartGeneration", 8)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, panel.visibility)
        assertNull(get("pendingVideoFailure"))
    }

    @Test fun oldOwnerAndStoppedSessionCannotFailOrRetry() {
        owner(null)
        failVideo(7)
        assertNull(get("pendingVideoFailure"))
        owner(activity)
        failVideo(7)
        (get("shuttingDown") as AtomicBoolean).set(true)
        clearInvocations(sink)
        assertFalse(retry())
        verifyNoInteractions(sink)
    }

    @Test fun hevcFailureKeepsFallbackPendingWithoutStartingAReconnect() {
        `when`(sink.currentVideoFailure()).thenReturn(VideoCodec.H265 to "test failure")
        activity.javaClass.getDeclaredMethod("onVideoFailure", Int::class.javaPrimitiveType,
            VideoCodec::class.java, String::class.java).apply { isAccessible = true }
            .invoke(activity, 7, VideoCodec.H265, "test failure")
        assertEquals(7 to VideoCodec.H265, get("pendingVideoFailure"))
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(7, get("restartGeneration"))
        verify(sink, never()).close()
    }

    private fun failVideo(generation: Int) {
        `when`(sink.currentVideoFailure()).thenReturn(VideoCodec.H264 to "test failure")
        invokeFailure(generation)
    }
    private fun invokeFailure(generation: Int) = activity.javaClass.getDeclaredMethod("onVideoFailure",
        Int::class.javaPrimitiveType, VideoCodec::class.java, String::class.java).apply { isAccessible = true }
        .invoke(activity, generation, VideoCodec.H264, "test failure")
    private fun recover(generation: Int) = activity.javaClass.getDeclaredMethod("onVideoRecovered",
        Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, generation)
    private fun retry() = activity.javaClass.getDeclaredMethod("retryCurrentVideo")
        .apply { isAccessible = true }.invoke(activity) as Boolean
    private fun get(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun owner(value: CarPlayHostActivity?) = CarPlayBackgroundSession::class.java
        .getDeclaredField("owner").apply { isAccessible = true }.set(CarPlayBackgroundSession, value)
}
