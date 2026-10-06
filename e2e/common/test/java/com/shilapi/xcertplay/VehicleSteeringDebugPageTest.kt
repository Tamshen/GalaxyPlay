package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class VehicleSteeringDebugPageTest {
    private val activity = Robolectric.buildActivity(GalaxySettingsActivity::class.java).get().apply { setTheme(R.style.Theme_Xcertplay) }
    private lateinit var page: L7SteeringDebugPage
    @Before fun setup() {
        SteeringListening.stop("TEST_RESET")
        L7AudioTemplates.selectModel(activity, L7AudioTemplates.Model.L6)
        L7Agreement.accept(activity)
        page = L7SteeringDebugPage(activity, LinearLayout(activity)) {}
    }
    @After fun cleanup() { page.close(); shadowOf(Looper.getMainLooper()).idle() }
    private fun row(name: String): L7SettingRow = ReflectionHelpers.getField(page, name)
    private fun receive(): L7SteeringTrace {
        val trace = L7SteeringDiagnostics.begin("vehicle-broadcast", -1, "type=2")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        page.update()
        return trace
    }
    @Test fun openingPageDoesNotListenAndStartingWithoutInputDoesNotPrompt() {
        assertFalse(SteeringListening.active())
        row("toggle").performClick()
        assertTrue(SteeringListening.active())
        page.update()
        assertNull(SteeringListening.controller.snapshot().pending)
        assertEquals(activity.getString(R.string.l7_listen_waiting), row("status").valueView.text.toString())
    }
    @Test fun noInputReportDoesNotInventAnInputAndClosedPageCannotRestartListener() {
        row("toggle").performClick()
        L7SteeringDiagnostics.store.clear()
        row("noInput").performClick()
        assertTrue(L7SteeringDiagnostics.store.snapshot().events.isEmpty())
        assertNull(SteeringListening.controller.snapshot().pending)
        page.close()
        row("toggle").performClick()
        assertFalse(SteeringListening.active())
    }
    @Test fun receivedInputPromptsThenLabelsThatExactTrace() {
        row("toggle").performClick()
        val trace = receive()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        shadowOf(dialog.listView).performItemClick(1)
        assertFalse(dialog.isShowing)
        assertTrue(L7SteeringDiagnostics.store.snapshot().events.any { it.id == trace.id && it.stage == "USER_LABEL" && "key=RIGHT" in it.detail })
        assertTrue(SteeringListening.active())
    }
    @Test fun ignoringAndBackgroundNeverMislabelAnInputOrResumeListening() {
        row("toggle").performClick()
        val trace = receive()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        page.background()
        assertFalse(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(SteeringListening.active())
        assertTrue(L7SteeringDiagnostics.store.snapshot().events.none { it.id == trace.id && it.stage == "USER_LABEL" })
        page.resume(); page.update()
        assertFalse(SteeringListening.active())
    }
}
