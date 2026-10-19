package com.shilapi.xcertplay

import android.app.Activity
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** 验证真实引导入口、阶段显示和日志入口；不启动 USB 或主动上传。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], qualifiers = "zh-rCN", manifest = Config.NONE)
class GalaxyWiredConnectionGuideTest {
    @Test fun guideExplainsPairingImpactAndOnlyRecordsExplicitActions() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
        val events = mutableListOf<String>()
        var logsOpened = 0
        val panel = L7ConnectionPanel(activity, false, {}, {}, {}, { logsOpened++ }, { events += it })
        activity.setContentView(panel)
        assertTrue(events.isEmpty())
        panel.usbGuideButton.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        val body = text(dialog.window!!.decorView)
        assertTrue(body.contains("忽略这辆车"))
        assertTrue(body.contains("需要重新配对"))
        assertTrue(body.contains("无法自动确认"))
        assertEquals(listOf("USB guide action=GUIDE_OPENED"), events)
        assertEquals(0, logsOpened)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, logsOpened)
        assertEquals("USB guide action=OPEN_LOGS", events.last())
        owner.pause().stop().destroy()
    }

    @Test fun removingPanelDismissesGuideAndWirelessHasNoUsbGuide() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get().apply { setTheme(android.R.style.Theme_Material_NoActionBar) }
        val panel = L7ConnectionPanel(activity, false, {}, {}, {})
        activity.setContentView(panel)
        panel.usbGuideButton.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        activity.setContentView(View(activity))
        assertFalse(dialog.isShowing)
        assertEquals(View.GONE, L7ConnectionPanel(activity, true, {}, {}, {}).usbGuideButton.visibility)
        owner.pause().stop().destroy()
    }

    @Test fun stageFollowsActualPermissionAndModeState() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(context.getString(R.string.galaxy_usb_stage_permission),
            GalaxyWiredConnectionProgress.describe(context, CarPlayStatus.RequestingIphonePermission, "fallback"))
        assertEquals(context.getString(R.string.galaxy_usb_stage_switch),
            GalaxyWiredConnectionProgress.describe(context, CarPlayStatus.SelectingConfiguration, "fallback"))
        assertEquals(context.getString(R.string.galaxy_usb_stage_reenumeration),
            GalaxyWiredConnectionProgress.describe(context, CarPlayStatus.WaitingForReenumeration, "fallback"))
        assertEquals("specific failure", GalaxyWiredConnectionProgress.describe(context,
            CarPlayStatus.Failed("error", usbModeSwitchFailed = true), "specific failure"))
    }

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }
}
