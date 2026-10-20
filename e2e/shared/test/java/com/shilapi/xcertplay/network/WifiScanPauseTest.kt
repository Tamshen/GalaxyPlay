package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.adb.LocalAdb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WifiScanPauseTest {
    @Test fun establishedSessionOwnsScanGuardAndEndedOrClosedSessionRejectsLateWork() {
        val currentChecks = mutableListOf<() -> Boolean>()
        var starts = 0; var ends = 0
        val control = object : WifiScanPauseControl {
            override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {}
            override fun release(app: Context, owner: Any, log: (String) -> Unit) {}
            override fun sessionActive(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
                starts++; currentChecks.add(current)
            }
            override fun sessionEnded(app: Context, owner: Any, log: (String) -> Unit) { ends++ }
        }
        val pause = WifiScanPause(context, {}, control)
        pause.pause(); assertEquals(0, starts)
        pause.sessionActive(); assertTrue(currentChecks.last()())
        pause.sessionEnded(); assertEquals(1, ends); assertTrue(currentChecks.none { it() })
        pause.sessionActive(); assertEquals(2, starts)
        pause.close(); pause.sessionActive(); assertEquals(2, starts); assertTrue(currentChecks.none { it() })
    }
    @Test fun periodicSnapshotKeepsTheActualFailureAndLateResultsCannotOverwriteAClosedOwner() {
        var callback: ((String) -> Unit)? = null
        val control = object : WifiScanPauseControl {
            override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) { callback = log }
            override fun release(app: Context, owner: Any, log: (String) -> Unit) {}
        }
        val logs = mutableListOf<String>()
        val pause = WifiScanPause(context, logs::add, control)
        pause.pause()
        assertTrue(pause.diagnosticSnapshot().contains("scanPauseResult=pending"))
        callback!!("Wi-Fi connectivity scans paused=false reason=adb-off")
        assertTrue(pause.diagnosticSnapshot().contains("paused=false reason=adb-off"))
        pause.close()
        callback!!("Wi-Fi connectivity scans paused=true reason=done")
        assertEquals(1, logs.size)
        assertTrue(pause.diagnosticSnapshot().contains("scanPauseClosed=true"))
        assertTrue(pause.diagnosticSnapshot().contains("paused=false reason=adb-off"))
    }
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun controllerCloseInvalidatesQueuedPausesAndReleasesItsOwnLeaseOnce() {
        val acquisitions = mutableListOf<Pair<Any, () -> Boolean>>()
        val releases = mutableListOf<Any>()
        val control = object : WifiScanPauseControl {
            override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
                acquisitions.add(owner to current)
            }

            override fun release(app: Context, owner: Any, log: (String) -> Unit) {
                releases.add(owner)
            }
        }
        val pause = WifiScanPause(context, {}, control)
        pause.pause()
        pause.pause()
        assertEquals(2, acquisitions.size)
        assertEquals(acquisitions[0].first, acquisitions[1].first)
        assertTrue(acquisitions.all { it.second() })
        pause.close()
        pause.close()
        pause.pause()
        assertEquals(2, acquisitions.size)
        assertEquals(listOf(acquisitions[0].first), releases)
        assertTrue(acquisitions.none { it.second() })
    }

    @Test
    fun onlyWifiDirectPausesTheSearchOnEverySupportedRelease() {
        for (backend in WirelessHotspotBackend.entries) {
            for (sdk in 24..36) {
                assertEquals("$backend on API $sdk",
                    backend == WirelessHotspotBackend.WIFI_P2P && sdk >= 25, WifiScanPause.eligible(backend, sdk))
            }
        }
    }

    @Test
    fun startsTheHelperFromTheInstalledApkAsTheShellUser() {
        assertEquals(
            "CLASSPATH='/data/app/~~a/com.shihab.diplay-b/base.apk' app_process /system/bin " +
                "com.shilapi.xcertplay.network.WifiScanPauseMain pause",
            WifiScanPause.command("/data/app/~~a/com.shihab.diplay-b/base.apk", enabled = false),
        )
        assertEquals(
            "CLASSPATH='/data/it'\"'\"'s/base.apk' app_process /system/bin " +
                "com.shilapi.xcertplay.network.WifiScanPauseMain restore",
            WifiScanPause.command("/data/it's/base.apk", enabled = true),
        )
    }

    @Test
    fun readsOnlyTheHelperProtocolLine() {
        for (result in WifiScanSwitchResult.entries) {
            assertEquals(result, WifiScanPause.parse("WARNING: linker noise\nDIPLAY_WIFI_SCAN_V1|${result.name}\n"))
        }
        assertEquals(WifiScanSwitchResult.UNKNOWN, WifiScanPause.parse(null))
        assertEquals(WifiScanSwitchResult.UNKNOWN, WifiScanPause.parse(""))
        assertEquals(WifiScanSwitchResult.UNKNOWN, WifiScanPause.parse("Error: Could not find class"))
        assertEquals(WifiScanSwitchResult.UNKNOWN, WifiScanPause.parse("DIPLAY_WIFI_SCAN_V1|PAUSED_MAYBE"))
        assertEquals(WifiScanSwitchResult.UNKNOWN, WifiScanPause.parse("Result: Parcel(00000000    '....')"))
    }

    @Test
    fun aMissingReplyReportsWhatAdbSaid() {
        assertEquals(WifiScanSwitchResult.ADB_NOT_APPROVED, WifiScanPause.unanswered(LocalAdb.Access.NOT_APPROVED))
        assertEquals(WifiScanSwitchResult.ADB_OFF, WifiScanPause.unanswered(LocalAdb.Access.UNREACHABLE))
        assertEquals(WifiScanSwitchResult.ADB_PAIRING_ONLY, WifiScanPause.unanswered(LocalAdb.Access.UNSUPPORTED))
        // A connected shell may have run the helper before the link dropped.
        assertEquals(WifiScanSwitchResult.UNKNOWN, WifiScanPause.unanswered(LocalAdb.Access.READY))
        assertEquals(WifiScanSwitchResult.UNKNOWN, WifiScanPause.unanswered(null))
    }
}
