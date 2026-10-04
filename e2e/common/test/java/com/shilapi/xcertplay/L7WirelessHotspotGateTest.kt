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
    private class Access(private val granted: Boolean) : L7HotspotTask.Access {
        override fun read() = NativeHotspotRead(NativeHotspotCredentials("native", "TestPassword123", ManualHotspotSecurity.WPA2))
        override fun apply(credentials: NativeHotspotCredentials): NativeHotspotProblem? = error("连接预检不应写入新配置")
        override fun enabled() = false
        override fun permitted() = granted
        override fun start(cancelled: () -> Boolean) = CarHotspotTethering.Result.READY
    }

    @Test fun readyContinuesExactlyOnce() = scenario(true, false, 1)
    @Test fun missingPermissionCannotContinueConnection() = scenario(false, false, 0)
    @Test fun leavingHostDiscardsCompletion() = scenario(true, true, 0)

    private fun scenario(grant: Boolean, cancel: Boolean, expected: Int) {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = host.get()
        L7Agreement.accept(activity)
        val task = L7HotspotTask(activity, Access(grant))
        val gate = L7WirelessHotspotGate(activity, task)
        var connections = 0
        try {
            gate.start({ connections++ }, {})
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
