package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydBatteryTest {
    // Replies from the car on 2026-09-28: 25 %, 150 km, 25.1 kWh left, not charging (BMS state 15).
    private val car = mapOf(
        "1246777400" to "Result: Parcel(00000000 41c80000   '.......A')",
        "1246765118" to "Result: Parcel(00000000 00000096   '........')",
        "882901008" to "Result: Parcel(00000000 41c8cccd   '.......A')",
        "876609560" to "Result: Parcel(00000000 0000000f   '........')",
    )

    private fun shell(replies: Map<String, String>): (String) -> String? = { command ->
        replies.entries.firstOrNull { command.endsWith(" ${it.key}") }?.value
    }

    @Test
    fun readsTheCarsReplies() {
        val reading = BydBattery.read(shell(car))!!

        assertEquals(25.0, reading.percent, 0.001)
        assertEquals(150, reading.rangeKm)
        assertEquals(25.1, reading.remainingKwh, 0.001)
        assertFalse(reading.charging)
    }

    @Test
    fun noAdbAccessGivesNoReading() {
        // BydAdbShell answers null while adbd is off, refuses DiPlay's key or waits for approval.
        assertNull(BydBattery.read { null })
    }

    @Test
    fun aMissingOrRejectedValueGivesNoReading() {
        assertNull(BydBattery.read(shell(car - "1246765118")))
        assertNull(BydBattery.read(shell(car + ("1246777400" to "Result: Parcel(ffffd8e5    '....')"))))
    }

    @Test
    fun theIphoneGetsWattHoursAndAWarningAtTheThreshold() {
        val reading = BydBatteryReading(percent = 25.0, rangeKm = 150, remainingKwh = 25.1, charging = false)

        val above = BydBattery.snapshot(reading, lowPercent = 20, fullKwh = null)
        assertFalse(above.rangeWarning)
        assertEquals(25_100L, above.currentChargeWh)
        assertEquals(100_400L, above.maxChargeWh)
        assertEquals(600, above.maxRangeKm)

        assertTrue(BydBattery.snapshot(reading, lowPercent = 25, fullKwh = null).rangeWarning)
    }

    @Test
    fun aLowChargeKeepsTheEarlierFullChargeEstimate() {
        val high = BydBatteryReading(percent = 80.0, rangeKm = 480, remainingKwh = 80.0, charging = false)
        val low = BydBatteryReading(percent = 5.0, rangeKm = 30, remainingKwh = 5.6, charging = false)

        assertNull(BydBattery.fullKwh(low))
        assertEquals(100_000L, BydBattery.snapshot(low, lowPercent = 20, fullKwh = BydBattery.fullKwh(high)).maxChargeWh)
    }
}
