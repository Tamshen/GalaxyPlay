package com.shilapi.xcertplay.network

import android.net.wifi.WifiManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class WirelessPerformanceLockTest {
    private fun held(owner: WirelessPerformanceLock, name: String) =
        WirelessPerformanceLock::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(owner) as? WifiManager.WifiLock

    @Test fun repeatedActivationReusesLocksAndDisconnectReleasesBoth() {
        val events = mutableListOf<String>()
        val owner = WirelessPerformanceLock(RuntimeEnvironment.getApplication(), events::add)
        owner.acquire()
        val high = held(owner, "highPerformance")!!
        val low = held(owner, "lowLatency")!!
        assertTrue(high.isHeld && low.isHeld)
        assertTrue(owner.diagnosticSnapshot().contains("wifiLockHighHeld=true wifiLockLowHeld=true"))
        assertTrue(owner.diagnosticSnapshot().contains("wifiLockEvidence=request_only"))
        owner.acquire()
        assertSame(high, held(owner, "highPerformance"))
        assertEquals(2, events.size)
        owner.release()
        owner.release()
        assertFalse(high.isHeld || low.isHeld)
        assertTrue(owner.diagnosticSnapshot().contains("wifiLockHighHeld=false wifiLockLowHeld=false"))
        owner.acquire()
        assertTrue(held(owner, "highPerformance")!!.isHeld)
        owner.close()
        assertNull(held(owner, "highPerformance"))
    }

    @Test fun diagnosticFailureDoesNotLeakLocksAndLateActivationAfterCloseIsRejected() {
        val owner = WirelessPerformanceLock(RuntimeEnvironment.getApplication()) { error("synthetic") }
        owner.acquire()
        val high = held(owner, "highPerformance")!!
        val low = held(owner, "lowLatency")!!
        owner.close()
        owner.close()
        owner.acquire()
        assertFalse(high.isHeld || low.isHeld)
        assertNull(held(owner, "highPerformance"))
        assertNull(held(owner, "lowLatency"))
    }
}
