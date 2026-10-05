package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class L7MediaSourcePolicyTest {
    class Online { companion object { const val SOURCE_TYPE_ONLINE = 6 } }
    class Incompatible { companion object { const val SOURCE_TYPE_ONLINE = 99 } }
    @Test fun easFirmwareUsesOwnOnlineCategoryWithoutBorrowingBluetoothOrUsb() {
        assertEquals(6, L7MediaSourcePolicy.resolve(1, Online::class.java))
        assertEquals(13, L7MediaSourcePolicy.resolve(null, Online::class.java))
        assertEquals(13, L7MediaSourcePolicy.resolve(0, Online::class.java))
    }
    @Test(expected = IllegalStateException::class)
    fun incompatibleOnlineConstantCannotSilentlyChangeCategory() {
        L7MediaSourcePolicy.resolve(1, Incompatible::class.java)
    }
}
