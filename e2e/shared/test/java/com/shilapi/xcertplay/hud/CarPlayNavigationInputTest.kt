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
        assertEquals(CarPlayNavigationSnapshot(true, "中文路名"), input.snapshot())
        assertNull(input.accept(Iap2Frame(0x5001, byteArrayOf())))
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
}
