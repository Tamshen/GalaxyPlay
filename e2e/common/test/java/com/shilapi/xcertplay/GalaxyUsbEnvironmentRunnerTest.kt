package com.shilapi.xcertplay

import android.app.Activity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GalaxyUsbEnvironmentRunnerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun awaitCondition(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(condition())
    }
    @After fun finish() { L7ProbeRunner.stop(); awaitCondition { !L7ProbeRunner.busy } }

    @Test fun selectedEvidenceUsesExistingReportAndLogsWithoutUpload() {
        L7Agreement.accept(app)
        val upload = RemoteLogUpload.status
        assertTrue(L7ProbeRunner.start(app, emptyMap(), "USB-BOUNDARIES"))
        awaitCondition { !L7ProbeRunner.busy }
        val report = L7ProbeRunner.current!!
        assertEquals(L7ProbePhase.COMPLETED, report.phase)
        assertEquals(listOf("USB-BOUNDARIES"), report.items.map { it.id })
        assertEquals(1, report.expected)
        assertFalse(L7ProbeRunner.storageFailed)
        assertFalse(L7ProbeRunner.logFailed)
        assertTrue(L7ProbeLog.read(app).any { "usbRoleSwitch=NOT_ATTEMPTED" in it })
        assertEquals(upload, RemoteLogUpload.status)
    }

    @Test fun cancelledObservationKeepsPartialEvidenceAndSuppressesHostStartWhileActive() {
        L7Agreement.accept(app)
        assertTrue(L7ProbeRunner.start(app, emptyMap(), "USB-OBSERVE-0"))
        awaitCondition { L7ProbeRunner.current?.items?.any { it.id == "USB-OBSERVE-0" && it.reason != "NOT_RUN" } == true }
        assertTrue(L7ProbeRunner.carPlayEnvironmentActive)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        assertFalse(L7StartupGuard.enterHost(activity.get()))
        assertTrue(activity.get().isFinishing)
        L7ProbeRunner.stop()
        awaitCondition { !L7ProbeRunner.busy }
        val report = L7ProbeRunner.current!!
        assertEquals(L7ProbePhase.CANCELLED, report.phase)
        assertEquals(21, report.expected)
        assertTrue(report.items.any { it.reason != "NOT_RUN" })
        assertTrue(report.items.any { it.reason == "NOT_RUN" })
        assertFalse(L7ProbeRunner.carPlayEnvironmentActive)
        assertTrue(L7ProbeLog.read(app).any { "phase=CANCELLED" in it })
        activity.pause().stop().destroy()
    }
}
