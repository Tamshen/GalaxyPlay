package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class L7NavigationPortTest {
    private val app = RuntimeEnvironment.getApplication()
    private fun broadcasts() = shadowOf(app).broadcastIntents.filter {
        it.action == "AUTONAVI_STANDARD_BROADCAST_SEND"
    }
    private val route = CarPlayNavigationSnapshot(true, "下一道路", currentRoad = "当前道路",
        maneuverType = 1, segmentRemainingMeters = 321, routeRemainingMeters = 5_000,
        routeRemainingSeconds = 600)

    @Test fun broadcastsAreTypedUntargetedAndNeedNoVendorSdk() {
        val port = GalaxyAmapNavigation(app)
        port.initialize(); port.start(); port.guidance(route); port.invalidate(); port.stop(); port.close()
        val sent = broadcasts()
        assertEquals(listOf(10019, 10001, 10019), sent.map { it.getIntExtra("KEY_TYPE", -1) })
        assertEquals(8, sent[0].getIntExtra("EXTRA_STATE", -1))
        assertEquals(9, sent[2].getIntExtra("EXTRA_STATE", -1))
        val info = sent[1]
        assertEquals(0, info.getIntExtra("TYPE", -1))
        assertEquals("当前道路", info.getStringExtra("CUR_ROAD_NAME"))
        assertEquals("下一道路", info.getStringExtra("NEXT_ROAD_NAME"))
        assertEquals(2, info.getIntExtra("NEW_ICON", -1))
        assertEquals(321, info.getIntExtra("SEG_REMAIN_DIS", -1))
        assertEquals(600, info.getIntExtra("ROUTE_REMAIN_TIME", -1))
        assertTrue(sent.all { it.`package` == null && it.component == null })
    }
    @Test fun oldOwnerCannotPublishOrStopNewSession() {
        val old = GalaxyAmapNavigation(app)
        val next = GalaxyAmapNavigation(app)
        old.start(); next.start()
        old.guidance(route); old.invalidate(); old.stop(); old.close()
        next.guidance(route); next.invalidate(); next.stop(); next.close()
        assertEquals(listOf(10019, 10019, 10001, 10019), broadcasts().map { it.getIntExtra("KEY_TYPE", -1) })
    }
    @Test fun invalidationRejectsLateGuidanceAndRepeatedStopDoesNotEndAnotherRoute() {
        val port = GalaxyAmapNavigation(app)
        port.start(); port.invalidate(); port.guidance(route); port.start(); port.stop(); port.stop(); port.close()
        assertEquals(2, broadcasts().size)
    }
}
