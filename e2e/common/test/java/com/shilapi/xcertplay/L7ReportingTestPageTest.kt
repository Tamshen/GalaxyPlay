package com.shilapi.xcertplay

import android.app.AlertDialog
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7ReportingTestPageTest {
    private lateinit var activity: DiPlayActivity
    private lateinit var page: L7ReportingTestPage
    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(R.style.Theme_Xcertplay)
        L7Agreement.accept(activity)
        page = L7ReportingTestPage(activity, LinearLayout(activity), L7ReportingKind.MEDIA) {}
    }
    @After fun cleanup() { page.close(); CarPlayBackgroundSession.clear() }
    private fun row(name: String): L7SettingRow = ReflectionHelpers.getField(page, name)
    private fun dialog(): AlertDialog? = ReflectionHelpers.getField(page, "dialog")

    @Test fun entryAndCancelledConfirmationNeverStartReporting() {
        assertEquals(L7ReportingTestController.Phase.IDLE, L7ReportingTests.snapshot().phase)
        assertTrue(row("start").isEnabled)
        assertFalse(row("end").isEnabled)
        row("start").performClick()
        assertTrue(dialog()!!.isShowing)
        dialog()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(L7ReportingTestController.Phase.IDLE, L7ReportingTests.snapshot().phase)
    }
    @Test fun realSessionDisablesSyntheticStartAndShowsReason() {
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
            1440, 1920, Any()) { it() }
        page.update()
        assertFalse(row("start").isEnabled)
        assertFalse(row("end").isEnabled)
        assertEquals(activity.getString(R.string.l7_report_phone_busy), row("status").feedbackView.text.toString())
    }
    @Test fun backgroundAndPageRebuildDismissPendingConfirmation() {
        row("start").performClick()
        val pending = dialog()!!
        page.background()
        assertFalse(pending.isShowing)
        pending.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(L7ReportingTestController.Phase.IDLE, L7ReportingTests.snapshot().phase)
        assertEquals(L7ReportingTestController.Phase.IDLE, L7ReportingTests.snapshot().phase)
        page.close()
        pending.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(L7ReportingTestController.Phase.IDLE, L7ReportingTests.snapshot().phase)
    }
    @Test fun entryHidesUpdateActionsAndTechnicalDetailsUntilRequested() {
        val actions = ReflectionHelpers.getField<LinearLayout>(page, "actionsSection")
        assertEquals(android.view.View.GONE, actions.visibility)
        assertEquals(activity.getString(R.string.l7_report_begin_first), row("evidence").valueView.text.toString())
        val details = ReflectionHelpers.getField<L7DebugDetails>(page, "details")
        val body = ReflectionHelpers.getField<L7SettingRow>(details, "body")
        assertEquals(android.view.View.GONE, body.visibility)
    }

}
