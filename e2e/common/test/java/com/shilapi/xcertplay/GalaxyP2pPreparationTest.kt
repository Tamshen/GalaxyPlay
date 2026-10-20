package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.GalaxyP2pAutoJoin
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "zh-rCN")
class GalaxyP2pPreparationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun config(mode: WirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
                       transport: CarPlayTransport = CarPlayTransport.WIRELESS) = CarPlayRuntimeConfig(
        identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3),
        mfiTarget = MfiTarget.LOCAL, transport = transport, wirelessHotspotMode = mode,
        manualHotspotSsid = "synthetic", manualHotspotPassphrase = "Synthetic9",
        existingWifiSsid = "synthetic", existingWifiPassphrase = "Synthetic9")

    @Test fun directPreparationDoesNotDependOnVehicleOrProfileSelection() {
        val preferences = app.getSharedPreferences("l7_audio_templates", 0)
        val before = preferences.getString("model", null)
        try {
            for (model in listOf("l6", "l7", "custom", "unknown", null)) {
                preferences.edit().putString("model", model).commit()
                assertNotNull("车型 $model 应共用 Direct 准备", GalaxyP2pPreparation.forSession(config(), 30))
                assertNotNull(GalaxyP2pPreparation.beforeGroup(config(), 30))
            }
        } finally { preferences.edit().putString("model", before).commit() }
    }

    @Test fun onlyAndroid11WirelessDirectUsesTheMaintenanceInterface() {
        for (api in listOf(29, 31, 33)) assertNull(GalaxyP2pPreparation.beforeGroup(config(), api))
        for (mode in WirelessHotspotMode.entries.filter { it != WirelessHotspotMode.WIFI_P2P })
            assertNull(GalaxyP2pPreparation.beforeGroup(config(mode), 30))
        assertNull(GalaxyP2pPreparation.beforeGroup(config(transport = CarPlayTransport.WIRED), 30))
        for (api in listOf(29, 31, 33))
            assertNull(GalaxyP2pPreparation.forSession(config(), api))
        for (mode in WirelessHotspotMode.entries.filter { it != WirelessHotspotMode.WIFI_P2P })
            assertNull(GalaxyP2pPreparation.forSession(config(mode), 30))
        assertNull(GalaxyP2pPreparation.forSession(config(transport = CarPlayTransport.WIRED), 30))
    }

    @Test fun preparationFailureExplainsThePreparationStageInsteadOfNetworkSuccess() {
        val progress = GalaxyWirelessConnectionProgress()
        progress.describe(app, CarPlayStatus.StartingHotspot, "")
        val message = progress.describe(app, CarPlayStatus.Failed(GalaxyP2pAutoJoin.FAILURE,
            startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION), "")
        assertTrue(message.contains("1/5")); assertTrue(message.contains("自动加入准备未完成"))
        assertFalse(message.contains(GalaxyP2pAutoJoin.FAILURE))
    }
}
