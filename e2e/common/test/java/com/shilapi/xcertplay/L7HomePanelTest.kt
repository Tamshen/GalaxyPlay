package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
class L7HomePanelTest {
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    @Test fun firstUseHasThreeEntriesAndConfigurationAddsQuickConnectFirst() {
        val actions = mutableListOf<String>()
        val panel = L7HomePanel(activity(), { actions += "quick" }, { actions += "wireless" },
            { actions += "usb" }, { actions += "settings" })
        panel.update(false, false, false, false, null)
        assertEquals(View.GONE, panel.quick.visibility)
        assertEquals(listOf("无线连接", "有线连接", "设置"), listOf(panel.wireless, panel.usb, panel.settings).map { it.text.toString() })
        panel.wireless.performClick(); panel.usb.performClick(); panel.settings.performClick()
        assertEquals(listOf("wireless", "usb", "settings"), actions)
        panel.update(true, false, false, false, null)
        assertEquals(View.VISIBLE, panel.quick.visibility)
        assertEquals("立即连接", panel.quick.text.toString())
        assertTrue(panel.indexOfChild(panel.quick) < panel.indexOfChild(panel.wireless))
        panel.quick.performClick()
        assertEquals("quick", actions.last())
    }

    @Test fun activeSessionProvidesReturnEvenWithoutSavedConfigurationAndBlocksTransportSwitch() {
        val panel = L7HomePanel(activity(), {}, {}, {}, {})
        panel.update(false, true, false, false, null)
        assertEquals("查看连接进度", panel.quick.text.toString())
        assertFalse(panel.usb.isEnabled)
        assertTrue(panel.settings.isEnabled)
        panel.update(false, true, true, false, null)
        assertEquals("返回 CarPlay", panel.quick.text.toString())
        assertTrue(panel.quick.isEnabled)
        panel.update(true, false, false, false, "认证配置需要检查")
        assertFalse(panel.quick.isEnabled)
        assertTrue(panel.usb.isEnabled)
        assertTrue(panel.wireless.isEnabled)
        assertTrue(panel.settings.isEnabled)
    }

    @Test fun quickReadinessUsesSelectedTransportAndRequiresCompleteWirelessSetup() {
        val context = activity()
        assertFalse(L7HomePanel.configured(context))
        DiPlayPreferences.savePhone(context, "00:00:00:00:00:01", "测试手机")
        assertFalse(L7HomePanel.configured(context))
        AirPlayPersistence.saveManualHotspotSsid(context, "测试热点")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test-only")
        assertTrue(L7HomePanel.configured(context))
        AirPlayPersistence.saveManualHotspotPassphrase(context, "short")
        assertFalse(L7HomePanel.configured(context))
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertTrue(L7HomePanel.configured(context))
    }
}
