package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.*
import java.time.Duration
import org.junit.After
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
class L7HotspotActionsTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private class Access : L7HotspotTask.Access {
        var grant = true
        var writes = 0
        var starts = 0
        var result = CarHotspotTethering.Result.FAILED
        override fun read() = NativeHotspotRead(problem = NativeHotspotProblem.PERMISSION)
        override fun apply(credentials: NativeHotspotCredentials): NativeHotspotProblem? { writes++; return null }
        override fun enabled() = false
        override fun permitted() = grant
        override fun start(cancelled: () -> Boolean): CarHotspotTethering.Result { starts++; return result }
    }
    private val access = Access()
    private val task = L7HotspotTask(activity, access)
    private val actions = L7HotspotActions(activity, task)
    @After fun cleanup() { actions.close(); task.close() }
    private fun await() {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (task.status.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(task.status.busy)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }
    private fun labels(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()

    @Test fun autoReadIsQuietButExplicitFailureStaysVisibleUntilAcknowledged() {
        L7Agreement.accept(activity)
        L7HotspotSettings(activity, LinearLayout(activity), task, actions) {}
        await()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        actions.start(); await()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertTrue(labels(dialog.window!!.decorView).contains(activity.getString(R.string.l7_hotspot_not_started)))
        assertTrue(labels(dialog.window!!.decorView).any { it.contains(activity.getString(R.string.l7_hotspot_failed)) })
        assertEquals(activity.getString(R.string.open_car_hotspot_settings), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        actions.start(); assertEquals(1, access.starts)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(actions.showing)
    }

    @Test fun missingPermissionHasExplicitRecoveryAndNeverWritesOrStarts() {
        L7Agreement.accept(activity); access.grant = false
        actions.start(task.proposal("test-device")); await()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.l7_hotspot_grant), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals(0, access.writes); assertEquals(0, access.starts)
        assertFalse(task.status.configurationApplied)
    }

    @Test fun savedConfigurationAndActivationFailureCannotBeDisplayedAsSuccess() {
        L7Agreement.accept(activity); access.result = CarHotspotTethering.Result.TIMED_OUT
        actions.start(task.proposal("test-device")); await()
        val state = L7HotspotFeedback.state(activity, task.status, applying = true)
        assertTrue(task.status.configurationApplied)
        assertEquals(activity.getString(R.string.l7_hotspot_saved_not_started), state.message)
        assertTrue(state.error); assertTrue(state.result); assertFalse(state.showProgress)
        assertTrue(state.detail.contains(activity.getString(R.string.l7_hotspot_timeout)))
    }

    @Test fun onlyConfirmedHotspotStateProducesActivationSuccess() {
        L7Agreement.accept(activity); access.result = CarHotspotTethering.Result.READY
        actions.start(); await()
        val state = L7HotspotFeedback.state(activity, task.status)
        assertEquals(true, task.status.hotspotEnabled)
        assertEquals(activity.getString(R.string.l7_hotspot_started_title), state.message)
        assertFalse(state.error); assertFalse(state.result)
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }
}
