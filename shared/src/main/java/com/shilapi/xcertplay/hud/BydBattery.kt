package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The traction battery as BYD's statistic, power and charging devices report it. */
internal data class BydBatteryReading(
    val percent: Double,
    val rangeKm: Int,
    val remainingKwh: Double,
    val charging: Boolean,
)

/**
 * Reads the traction battery through the adb shell (autoservice binder), where apps would need a BYD
 * signature. Verified on DiLink 5.0 (Android 12); the percentage and remaining-energy ids follow
 * BYDMate's validated map, the range id is Statistic.ELEC_DRIVING_RANGE for this platform.
 */
internal object BydBattery {
    private const val PERCENT = "service call autoservice 7 i32 1014 i32 1246777400" // float %
    private const val RANGE = "service call autoservice 5 i32 1014 i32 1246765118" // km
    private const val REMAINING = "service call autoservice 7 i32 1005 i32 882901008" // float kWh
    private const val BMS_STATE = "service call autoservice 5 i32 1009 i32 876609560" // 1 = charging
    private const val BMS_CHARGING = 1

    /** One reading, or null when any value is missing or outside what a battery can report. */
    fun read(shell: (String) -> String?): BydBatteryReading? {
        val percent = BydParcel.value(shell(PERCENT))?.let(::float)?.takeIf { it in 0.0..100.0 } ?: return null
        val range = BydParcel.value(shell(RANGE))?.takeIf { it in 0..3000 } ?: return null
        val remaining = BydParcel.value(shell(REMAINING))?.let(::float)?.takeIf { it in 0.0..300.0 } ?: return null
        val bms = BydParcel.value(shell(BMS_STATE))
        return BydBatteryReading(percent, range, remaining, bms == BMS_CHARGING)
    }

    /**
     * What the iPhone gets. Full charge and full range are scaled up from the current values; below
     * 20 % the rounding of the percentage would make that scale jumpy, so [fullKwh] from an earlier,
     * higher reading is used when there is one.
     */
    fun snapshot(reading: BydBatteryReading, lowPercent: Int, fullKwh: Double?): VehicleStatusSnapshot {
        val fraction = reading.percent / 100
        val full = fullKwh ?: if (fraction > 0) reading.remainingKwh / fraction else reading.remainingKwh
        return VehicleStatusSnapshot(
            rangeKm = reading.rangeKm,
            rangeWarning = reading.percent <= lowPercent,
            batteryPercent = reading.percent,
            currentChargeWh = (reading.remainingKwh * 1000).roundToLong(),
            maxChargeWh = (full * 1000).roundToLong(),
            maxRangeKm = if (fraction > 0) (reading.rangeKm / fraction).roundToInt() else reading.rangeKm,
            charging = reading.charging,
        )
    }

    /** A full-charge estimate from [reading], when the percentage is high enough to trust it. */
    fun fullKwh(reading: BydBatteryReading): Double? =
        if (reading.percent >= 20) reading.remainingKwh / (reading.percent / 100) else null

    private fun float(bits: Int): Double = java.lang.Float.intBitsToFloat(bits).toDouble()
}

/**
 * Optional, needs ADB over network: tells the iPhone the car's charge and range, so Apple Maps can
 * warn about a low charge and suggest chargers. Reads the battery every 30 s while the iPhone asks
 * for vehicle status; the iAP2 loop only takes the last reading, so it never waits for adb.
 */
internal object BydBatteryStatus : VehicleStatusProvider {
    private const val TAG = "DiPlay-BYD-Battery"
    private const val READ_MILLIS = 30_000L
    private const val IDLE_MILLIS = 2 * 60_000L

    private val shell = BydAdbShell(TAG)
    @Volatile private var context: Context? = null
    private var started = false
    private val cache = BydBatteryCache(::now)
    @Volatile internal var readBattery: (Context) -> BydBatteryReading? = { app ->
        BydBattery.read { shell.run(app, it) }
    }
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-battery").apply { isDaemon = true }
    }
    @Volatile private var askedMillis = 0L

    @Synchronized
    fun start(appContext: Context) {
        context = appContext.applicationContext
        askedMillis = now()
        if (started) {
            // A reconnect after idle must not wait for the next 30-second tick.
            executor.execute(::poll)
        } else {
            started = true
            executor.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    override fun snapshot(): VehicleStatusSnapshot? {
        askedMillis = now()
        val app = context ?: return null
        return cache.snapshot(BydOutputSettings.lowChargePercent(app))
    }

    private fun poll() {
        val app = context ?: return
        // Nobody asked for a while: the session ended. Keep adb closed until the next one.
        if (now() - askedMillis > IDLE_MILLIS) return shell.close()
        val reading = readBattery(app) ?: return
        accept(app, reading)
    }

    /** Publish a settings check before telling the user that the battery is ready. No ADB I/O. */
    fun accept(appContext: Context, reading: BydBatteryReading) {
        context = appContext.applicationContext
        if (cache.accept(reading)) {
            Log.i(TAG, "battery ${reading.percent} % range ${reading.rangeKm} km ${reading.remainingKwh} kWh charging=${reading.charging}")
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
}

/** Atomically publishes the reading, timestamp and capacity estimate across both ADB readers. */
internal class BydBatteryCache(private val now: () -> Long) {
    private var latest: BydBatteryReading? = null
    private var latestMillis = 0L
    private var fullKwh: Double? = null

    @Synchronized
    fun accept(reading: BydBatteryReading): Boolean {
        val changed = latest?.let {
            it.percent.roundToInt() != reading.percent.roundToInt() || it.charging != reading.charging
        } != false
        BydBattery.fullKwh(reading)?.let { fullKwh = it }
        latest = reading
        latestMillis = now()
        return changed
    }

    @Synchronized
    fun snapshot(lowPercent: Int): VehicleStatusSnapshot? {
        val reading = latest ?: return null
        if (now() - latestMillis > 3 * 60_000L) return null
        return BydBattery.snapshot(reading, lowPercent, fullKwh)
    }
}
