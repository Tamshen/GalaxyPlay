package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class AmapNavigationProtocolTest {
    @Test fun convertsAppleTurnsWithoutUsingVendorHudCodes() {
        listOf(1 to 2, 2 to 3, 47 to 6, 48 to 7, 13 to 4, 14 to 5, 4 to 8,
            3 to 9, 6 to 11, 7 to 12, 10 to 15).forEach { (apple, icon) ->
            assertEquals(icon to 0, AmapNavigationProtocol.turn(apple, 0))
        }
        assertEquals(19 to 0, AmapNavigationProtocol.turn(4, 1))
        assertEquals(11 to 3, AmapNavigationProtocol.turn(30, 0))
        assertEquals(17 to 3, AmapNavigationProtocol.turn(30, 1))
        assertEquals(0 to 0, AmapNavigationProtocol.turn(null, 0))
        assertEquals(0 to 0, AmapNavigationProtocol.turn(255, 0))
    }
    @Test fun unknownFieldsAreOmittedAndNumbersStayIntWithoutWrapping() {
        val empty = AmapNavigationProtocol.guidance(CarPlayNavigationSnapshot(true))
        assertFalse(empty.containsKey("SEG_REMAIN_DIS"))
        assertFalse(empty.containsKey("ROUTE_REMAIN_TIME"))
        assertFalse(empty.containsKey("ETA_TEXT"))
        assertEquals(0, empty["NEW_ICON"])
        val fields = AmapNavigationProtocol.guidance(CarPlayNavigationSnapshot(true,
            routeRemainingMeters = Long.MAX_VALUE, routeRemainingSeconds = 0,
            segmentRemainingMeters = 300, arrivalEpochSeconds = Long.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, fields["ROUTE_REMAIN_DIS"])
        assertEquals(0, fields["ROUTE_REMAIN_TIME"])
        assertEquals(300, fields["SEG_REMAIN_DIS"])
        assertFalse(fields.containsKey("ETA_TEXT"))
    }
    @Test fun preservesCurrentAndNextRoadSeparatelyAndNeverSendsCoordinates() {
        val fields = AmapNavigationProtocol.guidance(CarPlayNavigationSnapshot(true, "旧展示路名",
            currentRoad = "当前路", nextRoad = "下一路", routeRemainingSeconds = 125, arrivalEpochSeconds = 1_800_000_000))
        assertEquals("当前路", fields["CUR_ROAD_NAME"])
        assertEquals("下一路", fields["NEXT_ROAD_NAME"])
        assertEquals(125, fields["ROUTE_REMAIN_TIME"])
        assertTrue((fields["ETA_TEXT"] as String).matches(Regex("\\d{2}:\\d{2}")))
        assertFalse(fields.keys.any { it.contains("LATITUDE") || it.contains("LONGITUDE") })
    }
}
