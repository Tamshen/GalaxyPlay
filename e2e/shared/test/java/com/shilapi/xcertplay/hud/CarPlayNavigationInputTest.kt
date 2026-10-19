package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class CarPlayNavigationInputTest {
    private var time = 0L
    private val input = CarPlayNavigationInput { time }
    private fun tlv(type: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size + 4).toByte(), 0, type.toByte()) + bytes
    private fun tlv(type: Int, vararg bytes: Int) = tlv(type, bytes.map(Int::toByte).toByteArray())
    private fun active() {
        input.accept(Iap2Frame(0x5202, tlv(1, 0, 1) + tlv(3, 2) + tlv(4, "中文路名\u0000".toByteArray())))
        input.accept(Iap2Frame(0x5201, tlv(1, 1) + tlv(0x0d, 0, 1)))
    }
    @Test fun routesWithoutDistanceAreValidAndPreservePhoneRoadText() {
        active()
        assertEquals("中文路名", input.snapshot().road)
        assertEquals(2, input.snapshot().maneuverType)
        assertNull(input.snapshot().segmentRemainingMeters)
        assertNull(input.accept(Iap2Frame(0x5001, byteArrayOf())))
    }

    @Test fun activeRoadReportsWithoutAnyManeuverDetailAndStopsOnArrival() {
        input.accept(Iap2Frame(0x5201, tlv(1, 1) + tlv(3, "当前路名\u0000".toByteArray())))
        assertEquals("当前路名", input.snapshot().currentRoad)
        assertNull(input.snapshot().nextRoad)
        input.accept(Iap2Frame(0x5201, tlv(1, 2)))
        assertFalse(input.snapshot().active)
    }

    @Test fun activeRouteWithoutRoadStillReportsStartAndMalformedUpdateDoesNotRefreshExpiry() {
        input.accept(Iap2Frame(0x5201, tlv(1, 1)))
        assertEquals(CarPlayNavigationSnapshot(true, null), input.snapshot())
        time = 30_000_000_000L
        input.accept(Iap2Frame(0x5201, byteArrayOf(0, 8, 0, 1, 1)))
        assertFalse(input.snapshot().active)
    }

    @Test fun diagnosticsDistinguishNoRouteAndMissingManeuverWithoutExposingRoadOrPayload() {
        val logs = mutableListOf<String>()
        val observed = CarPlayNavigationInput(report = logs::add)
        observed.accept(Iap2Frame(0x5201, tlv(1, 0)))
        observed.accept(Iap2Frame(0x5201, tlv(1, 1) + tlv(3, "隐私路名\u0000".toByteArray())))
        assertTrue(logs.any { "routeState=0" in it })
        assertTrue(logs.any { "routeState=1 roadField=true maneuverList=false" in it })
        assertFalse(logs.joinToString().contains("隐私路名"))
    }
    @Test fun arrivedAndDisconnectClearOldRouteEvenIfOnlyKeepaliveArrives() {
        active(); input.accept(Iap2Frame(0x5201, tlv(1, 2)))
        assertFalse(input.snapshot().active)
        input.clear(); input.accept(Iap2Frame(0x5201, tlv(0x0a, 0, 0, 0, 10)))
        assertFalse(input.snapshot().active)
    }
    @Test fun expiryAndMalformedFrameCannotExtendGuidanceForever() {
        active(); time = 30_000_000_000L
        input.accept(Iap2Frame(0x5201, byteArrayOf(0, 8, 0, 1, 1)))
        assertFalse(input.snapshot().active)
    }
    private fun number(value: Long, size: Int) = ByteArray(size) { i -> (value ushr ((size - 1 - i) * 8)).toByte() }

    @Test fun fullRouteKeepsUnitsAndUpdatesDistanceWithoutChangingRoad() {
        active()
        input.accept(Iap2Frame(0x5201, tlv(3, "当前路名\u0000".toByteArray()) +
            tlv(5, number(1_800_000_000, 8)) + tlv(6, number(600, 8)) +
            tlv(7, number(5_000, 4)) + tlv(10, number(300, 4))))
        val value = input.snapshot()
        assertEquals("当前路名", value.currentRoad)
        assertEquals("中文路名", value.nextRoad)
        assertEquals(300L, value.segmentRemainingMeters)
        assertEquals(5_000L, value.routeRemainingMeters)
        assertEquals(600L, value.routeRemainingSeconds)
        assertEquals(1_800_000_000L, value.arrivalEpochSeconds)
        input.accept(Iap2Frame(0x5201, tlv(10, number(100, 4))))
        assertEquals(100L, input.snapshot().segmentRemainingMeters)
    }
    @Test fun newManeuverWithoutDistanceAndMalformedTypedFieldsCannotReuseOldDistance() {
        active(); input.accept(Iap2Frame(0x5201, tlv(10, number(300, 4))))
        input.accept(Iap2Frame(0x5202, tlv(1, 0, 2) + tlv(3, 1)))
        input.accept(Iap2Frame(0x5201, tlv(13, 0, 2)))
        assertNull(input.snapshot().segmentRemainingMeters)
        assertEquals(1, input.snapshot().maneuverType)
        time = 30_000_000_000L
        input.accept(Iap2Frame(0x5201, tlv(6, 1)))
        assertFalse(input.snapshot().active)
    }
    @Test fun arrivalAndExpiryDropAllFieldsAndLateKeepaliveCannotRestartRoute() {
        active(); input.accept(Iap2Frame(0x5201, tlv(7, number(2_000, 4))))
        input.accept(Iap2Frame(0x5201, tlv(1, 2)))
        input.accept(Iap2Frame(0x5201, tlv(10, number(100, 4))))
        assertEquals(CarPlayNavigationSnapshot(), input.snapshot())
        input.accept(Iap2Frame(0x5201, tlv(1, 1)))
        assertNull(input.snapshot().routeRemainingMeters)
        assertNull(input.snapshot().maneuverType)
        assertNull(input.snapshot().segmentRemainingMeters)
    }
    @Test fun emptyManeuverListHidesOldTurnAfterGracePeriodWithoutEndingRoute() {
        active(); input.accept(Iap2Frame(0x5201, tlv(10, number(300, 4))))
        input.accept(Iap2Frame(0x5201, tlv(13, byteArrayOf())))
        time = 3_000_000_000L
        val value = input.snapshot()
        assertTrue(value.active)
        assertNull(value.maneuverType)
        assertNull(value.segmentRemainingMeters)
    }

}
