package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.transport.GalaxyUsbVerificationFailure
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyUsbVerificationSettingsTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val prefs get() = activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    @Before fun reset() {
        prefs.edit().clear().commit()
        activity.getSharedPreferences("galaxy_usb_verification_recovery", 0).edit().clear().commit()
    }
    private fun row(): L7SettingRow {
        val parent = LinearLayout(activity)
        GalaxyUsbVerificationSettings.add(activity, parent)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        return descendants(parent).filterIsInstance<L7SettingRow>().first()
    }
    @Test fun defaultUsesStandardAndCancelDoesNotSaveDraft() {
        assertFalse(GalaxyUsbVerificationSettings.enabled(activity))
        row().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 1)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(GalaxyUsbVerificationSettings.enabled(activity))
    }
    @Test fun explicitSelectionPersistsAndPendingRecoveryPreventsChangingBackend() {
        val row = row(); row.performClick()
        ShadowAlertDialog.getLatestAlertDialog().apply {
            listView.performItemClick(null, 1, 1)
            getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(GalaxyUsbVerificationSettings.enabled(activity))
        activity.getSharedPreferences("galaxy_usb_verification_recovery", 0).edit().putString("original", "adb").commit()
        row.performClick()
        ShadowAlertDialog.getLatestAlertDialog().apply {
            listView.performItemClick(null, 0, 0)
            getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(GalaxyUsbVerificationSettings.enabled(activity))
    }
    @Test fun everyFailureUsesFixedGuidanceAndIncludesItsReasonCode() {
        for (reason in GalaxyUsbVerificationFailure.Reason.entries) {
            val status = CarPlayStatus.Failed("private firmware error", usbVerificationReason = reason)
            val text = GalaxyWiredConnectionProgress.describe(activity, status, "private firmware error")
            assertTrue(text.contains(reason.name))
            assertFalse(text.contains("private"))
        }
        assertTrue(GalaxyUsbVerificationSettings.failure(activity, GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
            .contains(activity.getString(R.string.usb_verification_recovery_required)))
    }
}
