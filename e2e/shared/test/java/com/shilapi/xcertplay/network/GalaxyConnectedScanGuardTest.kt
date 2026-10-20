package com.shilapi.xcertplay.network

import android.content.Context
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyConnectedScanGuardTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private class Rig(val context: Context) : AutoCloseable {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val actions = mutableListOf<GalaxyScanAction>()
        var scanner = GalaxyScanState.ENABLED
        var duringDisable: () -> Unit = {}
        val guard = GalaxyConnectedScanGuard({ _, action ->
            actions += action
            when (action) {
                GalaxyScanAction.INSPECT -> scanner
                GalaxyScanAction.DISABLE -> { duringDisable(); scanner = GalaxyScanState.DISABLED; scanner }
                GalaxyScanAction.ENABLE -> { scanner = GalaxyScanState.ENABLED; scanner }
            }
        }, worker)
        fun idle() { worker.submit {}.get(5, TimeUnit.SECONDS) }
        override fun close() { worker.shutdownNow() }
    }

    @Test fun lastOwnerRestoresAndRapidReplacementRetainsThePause() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 10)
        Rig(context).use { rig ->
            val first = Any(); val second = Any()
            rig.guard.connected(context, first, { true }, {}); rig.idle()
            rig.guard.connected(context, second, { true }, {}); rig.idle()
            rig.guard.release(context, first, {}); rig.idle()
            assertEquals(GalaxyScanState.DISABLED, rig.scanner)
            assertEquals(1, rig.actions.count { it == GalaxyScanAction.DISABLE })
            rig.guard.release(context, second, {}); rig.idle()
            assertEquals(GalaxyScanState.ENABLED, rig.scanner)
            assertEquals(1, rig.actions.count { it == GalaxyScanAction.ENABLE })
        }
    }

    @Test fun cancellationBeforeWorkerStartsNeverChangesTheService() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 10)
        Rig(context).use { rig ->
            val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
            rig.worker.execute { entered.countDown(); proceed.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var current = true; val owner = Any()
            rig.guard.connected(context, owner, { current }, {})
            current = false; rig.guard.release(context, owner, {})
            proceed.countDown(); rig.idle()
            assertTrue(rig.actions.isEmpty())
        }
    }

    @Test fun disconnectDuringPrivilegedCallRestoresAfterItsLateReply() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 10)
        Rig(context).use { rig ->
            val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
            rig.duringDisable = { entered.countDown(); proceed.await(5, TimeUnit.SECONDS) }
            val owner = Any()
            rig.guard.connected(context, owner, { true }, {})
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            rig.guard.release(context, owner, {})
            proceed.countDown(); rig.idle()
            assertEquals(listOf(GalaxyScanAction.INSPECT, GalaxyScanAction.DISABLE, GalaxyScanAction.ENABLE), rig.actions)
            assertEquals(GalaxyScanState.ENABLED, rig.scanner)
        }
    }

    @Test fun restartRecoversButNewBootDiscardsOldRestoreResponsibility() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 10)
        val prefs = context.getSharedPreferences("galaxy_connected_scan_guard", Context.MODE_PRIVATE)
        prefs.edit().putInt("restore_enabled_boot", 10).commit()
        Rig(context).use { rig ->
            rig.scanner = GalaxyScanState.DISABLED
            rig.guard.recover(context); rig.idle()
            assertEquals(listOf(GalaxyScanAction.ENABLE), rig.actions)
            assertFalse(prefs.contains("restore_enabled_boot"))
        }
        prefs.edit().putInt("restore_enabled_boot", 9).commit()
        Rig(context).use { rig ->
            rig.guard.recover(context); rig.idle()
            assertTrue(rig.actions.isEmpty())
            assertFalse(prefs.contains("restore_enabled_boot"))
        }
    }

    @Test fun snapshotDistinguishesMissingListenerFromObservedScanCompletions() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 10)
        Rig(context).use { rig ->
            val owner = Any()
            rig.guard.connected(context, owner, { true }, {}); rig.idle()
            assertTrue(rig.guard.snapshot(owner).contains("LISTENER_UNAVAILABLE"))
            rig.guard.listening(owner); rig.guard.observe(owner, true); rig.guard.observe(owner, false)
            val result = rig.guard.snapshot(owner)
            assertTrue(result.contains("updatedScans=1"))
            assertTrue(result.contains("COMPLETION_BROADCAST_NOT_RF"))
            rig.guard.release(context, owner, {}); rig.idle()
        }
    }
}
