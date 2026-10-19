package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GalaxyWifiScanPauseTest {
    @Test fun adbUnavailableUsesHostShellForPauseAndFinalRestoreAcrossOwners() {
        val transport = WifiScanPauseTransport()
        val commands = mutableListOf<String>()
        var journal = false
        var closes = 0
        val session = WifiScanPauseSession({ enabled ->
            transport.switch({ WifiScanSwitchResult.ADB_OFF }) {
                GalaxyWifiScanShell.run(WifiScanPause.command("/data/app/test/base.apk", enabled), 30) {
                    object : GalaxyP2pAutoJoin.Shell {
                        override fun run(command: String): String {
                            commands += command
                            return "DIPLAY_WIFI_SCAN_V1|DONE"
                        }
                        override fun close() { closes++ }
                    }
                }
            }
        }, { journal }, { journal = it; true })
        val first = Any(); val second = Any()
        assertTrue(session.acquire(first) { true }.paused)
        assertEquals("host-shell", transport.lastTransport)
        assertTrue(journal)
        assertTrue(session.acquire(second) { true }.paused)
        assertTrue(session.release(first))
        assertEquals(1, commands.size)
        assertTrue(session.release(second))
        assertFalse(journal)
        assertEquals(2, closes)
        assertTrue(commands[0].endsWith(" pause"))
        assertTrue(commands[1].endsWith(" restore"))
    }

    @Test fun successfulDeniedAndUncertainPrimaryNeverRunSecondMutation() {
        val transport = WifiScanPauseTransport()
        for (result in listOf(WifiScanSwitchResult.DONE, WifiScanSwitchResult.DENIED,
                WifiScanSwitchResult.UNKNOWN, WifiScanSwitchResult.ALREADY_OFF, WifiScanSwitchResult.UNSUPPORTED)) {
            assertEquals(result, transport.switch({ result }) { error("不应重复发送") })
            assertEquals("adb", transport.lastTransport)
        }
    }

    @Test fun unavailableMaintenancePreservesAdbFailureAndUnsupportedApiNeverConnects() {
        assertNull(GalaxyWifiScanShell.run("synthetic", 29) { error("不应访问维护端口") })
        val transport = WifiScanPauseTransport()
        assertEquals(WifiScanSwitchResult.ADB_OFF, transport.switch({ WifiScanSwitchResult.ADB_OFF }) {
            GalaxyWifiScanShell.run("synthetic", 30) { throw IOException("不可达") }
        })
        assertEquals("adb", transport.lastTransport)
    }

    @Test fun lostReplyAfterWriteRemainsUncertainAndClosesShell() {
        var closed = false
        val result = GalaxyWifiScanShell.run("synthetic", 30) {
            object : GalaxyP2pAutoJoin.Shell {
                override fun run(command: String): String = throw IOException("执行后响应丢失")
                override fun close() { closed = true }
            }
        }
        assertEquals(WifiScanSwitchResult.UNKNOWN, result)
        assertTrue(closed)
    }
}
