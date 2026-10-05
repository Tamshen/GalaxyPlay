package com.shilapi.xcertplay

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class VehicleSteeringDebugPageTest {
    private val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply { setTheme(R.style.Theme_Xcertplay) }
    private val parent = LinearLayout(activity)
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun text(): String = views(parent).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun markerButton(): View {
        var view: View = views(parent).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.l7_steering_test_key) }
        while (!view.isClickable) view = view.parent as View
        return view
    }

    @Test fun captureStatusFollowsConfirmedModelAndPageEntryDoesNotSendCommands() {
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L7)
        L7SteeringDiagnostics.store.clear()
        val page = L7SteeringDebugPage(activity, parent) {}
        assertTrue(text().contains(activity.getString(R.string.l7_steering_l7_capture)))
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L6)
        page.update()
        assertTrue(text().contains(activity.getString(R.string.l7_steering_other_capture)))
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.CUSTOM)
        page.update()
        assertTrue(text().contains(L7AudioModelConfirmation.name(activity, L7AudioTemplates.Model.CUSTOM)))
        assertTrue(L7SteeringDiagnostics.store.snapshot().events.isEmpty())
    }

    @Test fun cancellingKeySelectionDoesNotCreateMarker() {
        L7SteeringDiagnostics.store.clear()
        L7SteeringDebugPage(activity, parent) {}
        markerButton().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(L7SteeringDiagnostics.store.snapshot().events.isEmpty())
    }

    @Test fun selectedPhysicalKeyAddsOnlyAManualMarker() {
        L7SteeringDiagnostics.store.clear()
        L7SteeringDebugPage(activity, parent) {}
        markerButton().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(dialog.listView).performItemClick(1)
        assertFalse(dialog.isShowing)
        val events = L7SteeringDiagnostics.store.snapshot().events
        assertEquals(listOf("INPUT", "MARK"), events.map { it.stage })
        assertEquals("TEST_KEY_RIGHT", events.last().detail)
        assertTrue(events.all { it.source == "manual-marker" })
    }
}
