package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7HotspotTaskTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val old = NativeHotspotCredentials("existing", "Oldpass12345", ManualHotspotSecurity.WPA2)
    private val fresh = NativeHotspotCredentials.generate("test-device")
    private lateinit var access: FakeAccess
    private lateinit var task: L7HotspotTask

    private class FakeAccess : L7HotspotTask.Access {
        var result = NativeHotspotRead(problem = NativeHotspotProblem.PERMISSION)
        var writeProblem: NativeHotspotProblem? = null
        var on = false
        var grant = true
        var writes = 0
        var starts = 0
        var blocker: (() -> Unit)? = null
        var startResult = CarHotspotTethering.Result.READY
        override fun read(): NativeHotspotRead { blocker?.invoke(); return result }
        override fun apply(credentials: NativeHotspotCredentials): NativeHotspotProblem? { writes++; return writeProblem }
        override fun enabled() = on
        override fun permitted() = grant
        override fun start(cancelled: () -> Boolean): CarHotspotTethering.Result { starts++; return startResult }
    }

    @Before fun before() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(L7Agreement.accept(context))
        AirPlayPersistence.saveNativeHotspotCredentials(context, old)
        access = FakeAccess()
        task = L7HotspotTask(context, access)
    }
    @After fun after() { task.close() }

    @Test fun enteringOnlyReadsAndKeepsSavedCredentialsWhenDenied() {
        assertTrue(task.read()); await()
        assertEquals(old.password, AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(R.string.l7_hotspot_read_permission, task.status.message)
        assertEquals(0, access.writes); assertEquals(0, access.starts)
    }

    @Test fun successfulReadSavesNamePasswordAndSecurityTogether() {
        val value = NativeHotspotCredentials("native", "Password123", ManualHotspotSecurity.WPA3_TRANSITION)
        access.result = NativeHotspotRead(value)
        task.read(); await()
        assertEquals(value.ssid, AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals(value.password, AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(value.security, AirPlayPersistence.loadManualHotspotSecurity(context))
        assertEquals(0, access.starts)
    }

    @Test fun rejectedWriteKeepsOldDetailsAndDoesNotStart() {
        access.writeProblem = NativeHotspotProblem.PERMISSION
        task.start(fresh); await()
        assertEquals(old.ssid, AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals(old.password, AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(R.string.l7_hotspot_write_permission, task.status.message)
        assertEquals(0, access.starts)
    }

    @Test fun acceptedConfigurationIsSavedEvenIfStartupFails() {
        access.startResult = CarHotspotTethering.Result.FAILED
        task.start(fresh); await()
        assertEquals(fresh.password, AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(1, access.writes); assertEquals(1, access.starts)
        assertEquals(R.string.l7_hotspot_failed, task.status.message)
    }

    @Test fun existingHotspotStartsWithoutReconfiguration() {
        access.result = NativeHotspotRead(old)
        access.on = true; access.grant = false
        task.start(); await()
        assertEquals(0, access.writes); assertEquals(1, access.starts)
        assertEquals(R.string.l7_hotspot_ready, task.status.message)
    }

    @Test fun missingGrantCannotWriteOrStart() {
        access.grant = false
        task.start(fresh); await()
        assertEquals(0, access.writes); assertEquals(0, access.starts)
        assertEquals(R.string.l7_hotspot_start_permission, task.status.message)
    }

    @Test fun runningHotspotIsNotReconfiguredOrRestarted() {
        access.on = true
        task.start(fresh); await()
        assertEquals(0, access.writes); assertEquals(0, access.starts)
        assertEquals(old.password, AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(R.string.l7_hotspot_already_on, task.status.message)
    }

    @Test fun failedApplyKeepsTheSameDraftForManualCopy() {
        val proposal = task.proposal("test-device")
        access.writeProblem = NativeHotspotProblem.PERMISSION
        task.start(proposal); await()
        assertSame(proposal, task.proposal("test-device"))
    }

    @Test fun cancellationDiscardsLateReadAndRejectsRepeatedOperation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        access.result = NativeHotspotRead(fresh)
        access.blocker = {
            entered.countDown()
            // 模拟无法立即中断的系统调用；晚到结果仍不能保存。
            while (release.count > 0) try { release.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        }
        task.read()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(task.start(fresh))
        task.cancel(); release.countDown()
        Thread.sleep(50)
        assertEquals(old.password, AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(0, access.writes); assertEquals(0, access.starts)
        assertEquals(R.string.l7_hotspot_cancelled, task.status.message)
    }

    @Test fun noConsentPreventsReadingAndMutation() {
        context.getSharedPreferences("l7_agreement", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(task.read()); assertFalse(task.start(fresh))
        assertEquals(0, access.writes); assertEquals(0, access.starts)
    }

    private fun await() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (task.status.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse("原生热点操作未结束", task.status.busy)
    }
}
