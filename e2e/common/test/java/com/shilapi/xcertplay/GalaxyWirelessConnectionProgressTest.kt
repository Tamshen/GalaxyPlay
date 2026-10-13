package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
class GalaxyWirelessConnectionProgressTest {
    private val progress = GalaxyWirelessConnectionProgress()
    private fun describe(status: CarPlayStatus) = progress.describe(RuntimeEnvironment.getApplication(), status, "阶段说明")

    @Test fun realStagesAdvanceFromPreparationToVideoAndFailureKeepsItsStep() {
        val stages = listOf(CarPlayStatus.StartingHotspot, CarPlayStatus.ConnectingBluetooth,
            CarPlayStatus.RunningWireless, CarPlayStatus.WaitingForWirelessNetwork(1), CarPlayStatus.PreparingWirelessVideo)
        stages.forEachIndexed { index, status -> assertTrue(describe(status).contains("${index + 1}/5")) }
        assertTrue(describe(CarPlayStatus.Failed("failed")).contains("5/5"))
        assertTrue(progress.restarting(RuntimeEnvironment.getApplication()).contains("1/5"))
        assertTrue(describe(CarPlayStatus.Failed("release pending")).contains("1/5"))
        assertTrue(describe(CarPlayStatus.DiscoveringMfi).contains("1/5"))
    }

    @Test fun tcpAndProtocolTimeoutsExplainTheDifferentFailureAndWiredStatusIsUnchanged() {
        describe(CarPlayStatus.WaitingForWirelessNetwork(1))
        val tcp = describe(CarPlayStatus.Failed("private error", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT))
        assertTrue(tcp.contains("4/5"))
        assertTrue(tcp.contains("未收到 iPhone"))
        assertFalse(tcp.contains("private error"))
        val protocol = describe(CarPlayStatus.Failed("private error", startupFailure = WirelessStartupFailure.AIRPLAY_PROTOCOL_TIMEOUT))
        assertTrue(protocol.contains("会话确认超时"))
        assertEquals("阶段说明", describe(CarPlayStatus.Pairing))
    }

    @Test fun authenticationDoesNotClaimNetworkOrVideoSuccess() {
        val authentication = describe(CarPlayStatus.RunningWireless)
        assertTrue(authentication.contains("正在认证"))
        assertFalse(authentication.contains("会话已建立"))
        assertTrue(describe(CarPlayStatus.NegotiatingWireless).contains("4/5"))
    }
}
