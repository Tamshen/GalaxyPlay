package com.shilapi.xcertplay

import android.app.Activity
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7WirelessHotspotGateTest {
    private class Access(private val granted: Boolean, private val afterStartOnly: Boolean = false) : L7HotspotTask.Access {
        private var started = false
        override fun read() = if (afterStartOnly && !started) NativeHotspotRead(problem = NativeHotspotProblem.PERMISSION)
            else NativeHotspotRead(NativeHotspotCredentials("native", "TestPassword123", ManualHotspotSecurity.WPA2))
        override fun apply(credentials: NativeHotspotCredentials): NativeHotspotProblem? = error("连接预检不应写入新配置")
        override fun enabled() = false
        override fun permitted() = granted
        override fun start(cancelled: () -> Boolean): CarHotspotTethering.Result {
            started = true
            return CarHotspotTethering.Result.READY
        }
    }

    @Test fun readyContinuesExactlyOnce() = scenario(true, false, 1)
    @Test fun missingPermissionCannotContinueConnection() = scenario(false, false, 0)
    @Test fun leavingHostDiscardsCompletion() = scenario(true, true, 0)

    @Test @Config(sdk = [29, 30]) fun missingInitialCredentialsCanContinueAfterThePostStartRead() =
        scenario(true, false, 1, afterStartOnly = true)

    private fun scenario(grant: Boolean, cancel: Boolean, expected: Int, afterStartOnly: Boolean = false) {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = host.get()
        L7Agreement.accept(activity)
        if (afterStartOnly) activity.getSharedPreferences("xcertplay_airplay", Activity.MODE_PRIVATE).edit().clear().commit()
        val task = L7HotspotTask(activity, Access(grant, afterStartOnly))
        val gate = L7WirelessHotspotGate(activity, task)
        var connections = 0
        try {
            gate.start({
                assertEquals("native", AirPlayPersistence.loadManualHotspotSsid(activity))
                assertEquals("TestPassword123", AirPlayPersistence.loadManualHotspotPassphrase(activity))
                connections++
            }, {})
            assertEquals(0, connections)
            if (cancel) gate.cancel()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (task.status.busy && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(task.status.busy)
            gate.update(); gate.update()
            assertEquals(expected, connections)
        } finally { gate.cancel(); task.close(); host.pause().stop().destroy() }
    }
}
