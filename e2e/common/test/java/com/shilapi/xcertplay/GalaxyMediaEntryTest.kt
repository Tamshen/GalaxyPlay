package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyMediaEntryTest {
    @Test fun playerReturnsToActiveSessionOnlyOnce() {
        val intent = Intent(GalaxyMediaEntry.PLAYER_ACTION)
        var opened = 0
        assertTrue(GalaxyMediaEntry.handle(intent, active = true, stopping = false) { opened++ })
        assertFalse(GalaxyMediaEntry.handle(intent, active = true, stopping = false) { opened++ })
        assertEquals(1, opened)
    }

    @Test fun staleCardAndStoppingSessionCannotStartProjection() {
        for ((active, stopping) in listOf(false to false, true to true, false to true)) {
            val intent = Intent(GalaxyMediaEntry.PLAYER_ACTION)
            assertTrue(GalaxyMediaEntry.handle(intent, active, stopping) { fail("过期入口不得启动连接") })
            assertNull(intent.action)
        }
    }

    @Test fun ordinaryHomeIntentDoesNotNavigateOrConsumeOtherActions() {
        val intent = Intent("synthetic.other")
        assertFalse(GalaxyMediaEntry.handle(intent, active = true, stopping = false) { fail("首页不得自动跳转") })
        assertEquals("synthetic.other", intent.action)
    }
}
