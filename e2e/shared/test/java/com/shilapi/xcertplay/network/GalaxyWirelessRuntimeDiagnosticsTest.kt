package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GalaxyWirelessRuntimeDiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun scan(updated: Boolean) {
        context.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, updated).putExtra("ssid", "private-network"))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun observesCompletionAndAgesWithoutScanningOrExportingNetworkIdentities() {
        var now = 1000L
        val logs = mutableListOf<String>()
        val diagnostics = GalaxyWirelessRuntimeDiagnostics(context, logs::add, { now }, { "stationMetricsScope=STA stationState=DISCONNECTED" })
        try {
            diagnostics.start(); diagnostics.start()
            scan(true)
            now = 1300; scan(false)
            now = 1600
            val snapshot = diagnostics.snapshot()
            assertTrue(snapshot.contains("scanEvents=2 updatedEvents=1 lastScanAgoMs=300 lastUpdatedScanAgoMs=600"))
            assertTrue(snapshot.contains("scanStart=not_observed"))
            assertTrue(snapshot.contains("stationMetricsScope=STA"))
            assertEquals(1, logs.count { "stage=LISTENER" in it })
            assertEquals(1, logs.count { "stage=SCAN_RESULTS" in it })
            assertFalse((logs + snapshot).joinToString().contains("private-network"))
            diagnostics.close(); diagnostics.close()
            now = 2000; scan(true); diagnostics.start()
            assertTrue(diagnostics.snapshot().contains("scanEvents=2 updatedEvents=1"))
            assertEquals(1, logs.count { "stage=STOP" in it })
        } finally { diagnostics.close() }
    }

    @Test fun observationFailureNeverLeaksExceptionTextOrChangesTheSession() {
        val diagnostics = GalaxyWirelessRuntimeDiagnostics(context, { error("report failed") },
            station = { throw SecurityException("private phone identity") })
        try {
            diagnostics.start()
            scan(true)
            val snapshot = diagnostics.snapshot()
            assertTrue(snapshot.contains("stationQuery=unavailable failureClass=SecurityException"))
            assertFalse(snapshot.contains("private"))
        } finally { diagnostics.close() }
    }

    @Test fun closeBeforeStartDoesNotRegisterAndTheNextSessionHasFreshCounters() {
        val logs = mutableListOf<String>()
        val old = GalaxyWirelessRuntimeDiagnostics(context, logs::add)
        old.close(); old.start(); scan(true)
        assertFalse(logs.any { "stage=LISTENER" in it || "stage=SCAN_RESULTS" in it })
        val next = GalaxyWirelessRuntimeDiagnostics(context, logs::add)
        try {
            next.start()
            assertTrue(next.snapshot().contains("scanEvents=0 updatedEvents=0"))
        } finally { next.close() }
    }
}
