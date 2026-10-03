package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.withVehicleStatusFrom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydBatteryReadinessTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reading = BydBatteryReading(25.0, 150, 25.1, false)
    private val identification = Iap2IdentificationConfig(
        name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
        firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
        vehicleStatusEnabled = true,
    )

    @Test
    fun delayedAdbApprovalPrimesTheNextConnectionFromTheSettingsCheck() {
        val unavailable = BydAdbAccess.readStatus(context) { null }
        assertNull(unavailable.batteryPercent)
        assertFalse(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)

        // A successful settings check after approval must prime the provider, not just its label.
        val ready = BydAdbAccess.readStatus(context) { command ->
            val bits = when (command.substringAfterLast(' ')) {
                "1246777400" -> "41c80000"
                "1246765118" -> "00000096"
                "882901008" -> "41c8cccd"
                "876609560" -> "0000000f"
                else -> "00000003"
            }
            "Result: Parcel(00000000 $bits   '........')"
        }
        assertEquals(25.0, ready.batteryPercent!!, 0.001)
        assertTrue(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)
        assertEquals(150, BydBatteryStatus.snapshot()!!.rangeKm)
        reconnectAfterIdleRefreshesWithoutWaitingForThePeriodicTick()
    }

    private fun reconnectAfterIdleRefreshesWithoutWaitingForThePeriodicTick() {
        BydBatteryStatus.readBattery = { reading }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        BydBatteryStatus.start(context)
        awaitReading()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        assertNull(BydBatteryStatus.snapshot())
        assertFalse(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)

        BydBatteryStatus.start(context)
        awaitReading()
        assertTrue(identification.withVehicleStatusFrom(BydBatteryStatus).vehicleStatusEnabled)
    }

    @Test
    fun staleReadingBecomesUsableAgainOnlyAfterFreshDataIsPublished() {
        var clock = 0L
        val cache = BydBatteryCache { clock }
        cache.accept(reading)
        clock += 181_000
        assertNull(cache.snapshot(20))
        cache.accept(BydBatteryReading(10.0, 60, 10.0, false))
        val fresh = cache.snapshot(20)!!
        assertEquals(10.0, fresh.batteryPercent, 0.001)
        assertEquals(100_400L, fresh.maxChargeWh)
    }

    private fun awaitReading() {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (BydBatteryStatus.snapshot() == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull("refresh should complete before the 30-second periodic tick", BydBatteryStatus.snapshot())
    }
}
