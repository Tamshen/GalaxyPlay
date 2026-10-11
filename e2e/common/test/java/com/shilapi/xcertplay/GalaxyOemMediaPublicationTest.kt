package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class GalaxyOemMediaPublicationTest {
    private val current = GalaxyMediaReportingChannel.Mode.CURRENT
    private val netease = GalaxyMediaReportingChannel.Mode.NETEASE_COMPAT

    @Test fun currentChannelRetainsExistingPlayRequestWithoutAdditionalFocusQuery() {
        var requests = 0
        assertTrue(GalaxyOemMediaPublication.requestPlay(current, "own", { error("默认模式不追加查询") }) { requests++; true })
        assertEquals(1, requests)
    }
    @Test fun compatibilityAlreadyOwnedFocusDoesNotRepeatOrRejectPlaybackRequest() {
        for (mode in GalaxyMediaReportingChannel.Mode.entries.filter { it != current }) {
            assertTrue(GalaxyOemMediaPublication.requestPlay(mode, "own", { "own" }) { error("已有播放权不能再次申请") })
        }
    }
    @Test fun foreignOrUnknownFocusStillRequiresActualAcceptedPlayRequest() {
        for (mode in GalaxyMediaReportingChannel.Mode.entries.filter { it != current }) {
            for (focus in listOf(null, "", "other")) {
                var requests = 0
                assertFalse(GalaxyOemMediaPublication.requestPlay(mode, "own", { focus }) { requests++; false })
                assertEquals(1, requests)
            }
        }
    }
    @Test fun neteaseSelectsItsSourceBeforeMetadataOnlyWhenOwnPlaybackIsConfirmed() {
        val calls = mutableListOf<String>()
        GalaxyOemMediaPublication.beforeState(netease, true, "own", { calls.add("focus"); "own" }) { calls.add("source") }
        calls.add("metadata")
        assertEquals(listOf("focus", "source", "metadata"), calls)
    }
    @Test fun metadataOnOtherFocusOrBluetoothNeverChangesVehicleSource() {
        for (focus in listOf(null, "", "other"))
            GalaxyOemMediaPublication.beforeState(netease, true, "own", { focus }) { error("其他来源不能被元数据更新切走") }
        GalaxyOemMediaPublication.beforeState(netease, false, "own", { error("蓝牙上报不查询或切源") }) { error("不能切源") }
    }
    @Test fun otherChannelsKeepSourceSelectionAfterPlayRequestInsteadOfEveryMetadataUpdate() {
        for (mode in GalaxyMediaReportingChannel.Mode.entries.filter { it != netease })
            GalaxyOemMediaPublication.beforeState(mode, true, "own", { error("不追加查询") }) { error("不追加切源") }
    }
}
