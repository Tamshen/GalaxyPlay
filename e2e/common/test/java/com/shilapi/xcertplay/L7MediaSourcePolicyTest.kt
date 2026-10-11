package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class L7MediaSourcePolicyTest {
    class Online { companion object { const val SOURCE_TYPE_ONLINE = 6; const val SOURCE_TYPE_LOCAL = 0 } }
    class Incompatible { companion object { const val SOURCE_TYPE_ONLINE = 99 } }
    class InvalidLocal { companion object { const val SOURCE_TYPE_LOCAL = 1 } }
    @Test fun easFirmwareUsesOwnOnlineCategoryWithoutBorrowingBluetoothOrUsb() {
        assertEquals(6, L7MediaSourcePolicy.resolve(1, Online::class.java))
        assertEquals(13, L7MediaSourcePolicy.resolve(null, Online::class.java))
        assertEquals(13, L7MediaSourcePolicy.resolve(0, Online::class.java))
    }
    @Test(expected = IllegalStateException::class)
    fun incompatibleOnlineConstantCannotSilentlyChangeCategory() {
        L7MediaSourcePolicy.resolve(1, Incompatible::class.java)
    }
    @Test fun compatibilityChannelsUseVerifiedSourceCategoriesWithoutAutomaticEasSelection() {
        for (eas in listOf(null, 0, 1)) {
            assertEquals(6, L7MediaSourcePolicy.resolve(eas, Online::class.java, GalaxyMediaReportingChannel.Mode.NETEASE_COMPAT))
            assertEquals(0, L7MediaSourcePolicy.resolve(eas, Online::class.java, GalaxyMediaReportingChannel.Mode.LOCAL))
        }
    }
    @Test fun missingOrWrongLocalConstantCannotPretendToSupportLocalMusic() {
        assertTrue(runCatching { L7MediaSourcePolicy.resolve(1, Incompatible::class.java, GalaxyMediaReportingChannel.Mode.LOCAL) }.isFailure)
        assertTrue(runCatching { L7MediaSourcePolicy.resolve(1, InvalidLocal::class.java, GalaxyMediaReportingChannel.Mode.LOCAL) }.isFailure)
    }
}
