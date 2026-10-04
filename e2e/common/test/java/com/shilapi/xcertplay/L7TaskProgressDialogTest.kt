package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import com.shilapi.xcertplay.host.R
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7TaskProgressDialogTest {
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }
    private fun tick() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun backRequestsConfirmationAndKeepWaitingDoesNotStop() {
        var stopped = 0
        val progress = L7TaskProgressDialog(activity(), R.string.l7_task_collect, R.string.l7_task_stop_collect,
            R.string.l7_task_end_collect, { L7TaskProgress(true, "checking", completed = 2, total = 5) }, { stopped++ })
        progress.show(); shadowOf(Looper.getMainLooper()).idle()
        val bar = views(progress.dialog.window!!.decorView).filterIsInstance<ProgressBar>().single()
        assertFalse(bar.isIndeterminate); assertEquals(40, bar.progress)
        progress.dialog.onBackPressed()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame(progress.dialog, confirmation)
        assertTrue(progress.dialog.isShowing)
        assertEquals(0, stopped)
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(progress.dialog.isShowing); assertEquals(0, stopped)
        progress.dismiss()
    }

    @Test fun headerCloseCanTerminateAndDoesNotLeavePollingAfterDismiss() {
        var stopped = 0
        var reads = 0
        val progress = L7TaskProgressDialog(activity(), R.string.l7_log_upload, R.string.l7_task_stop_upload,
            R.string.l7_task_end_upload, { reads++; L7TaskProgress(true, "uploading") }, { stopped++ })
        progress.show(); shadowOf(Looper.getMainLooper()).idle()
        views(progress.dialog.window!!.decorView).first { it is android.widget.ImageButton }.performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stopped); assertFalse(progress.dialog.isShowing)
        val captured = reads
        tick(); assertEquals(captured, reads)
    }

    @Test fun stopKeepsPartialResultVisibleAndCloseRemainsAvailableDuringCleanup() {
        var state = L7TaskProgress(true, "checking", completed = 3, total = 8)
        val progress = L7TaskProgressDialog(activity(), R.string.l7_task_collect, R.string.l7_task_stop_collect,
            R.string.l7_task_end_collect, { state }, {
                state = state.copy(running = false, waiting = true, message = "stopping")
            })
        progress.show(); shadowOf(Looper.getMainLooper()).idle()
        progress.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(progress.dialog.isShowing)
        assertEquals(View.GONE, progress.dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(progress.dialog.isShowing)
    }

    @Test fun retryIsOnlyAnExplicitFailureActionInTheSameWindow() {
        var retries = 0
        var state = L7TaskProgress(false, "failed", retry = true, error = true)
        val progress = L7TaskProgressDialog(activity(), R.string.l7_log_upload, R.string.l7_task_stop_upload,
            R.string.l7_task_end_upload, { state }, {}, onRetry = {
                retries++; state = L7TaskProgress(true, "uploading", completed = 2, total = 7)
            })
        progress.show(); shadowOf(Looper.getMainLooper()).idle(); tick()
        assertEquals(0, retries)
        val button = progress.dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(progress.dialog.context.getString(R.string.l7_log_retry), button.text.toString())
        button.performClick()
        assertEquals(1, retries); assertTrue(progress.dialog.isShowing)
        assertEquals(progress.dialog.context.getString(R.string.l7_task_stop_upload), button.text.toString())
        state = L7TaskProgress(false, "done", completed = 7, total = 7)
        tick()
        assertEquals(View.GONE, button.visibility)
        assertTrue(progress.dialog.isShowing)
        progress.dismiss()
    }

    @Test fun completionDuringExitConfirmationKeepsResultsAndNeverCancelsFinishedWork() {
        var state = L7TaskProgress(true, "checking")
        var stopped = 0
        var viewed = 0
        val progress = L7TaskProgressDialog(activity(), R.string.l7_task_collect, R.string.l7_task_stop_collect,
            R.string.l7_task_end_collect, { state }, { stopped++ }, onResult = { viewed++ })
        progress.show(); shadowOf(Looper.getMainLooper()).idle(); progress.dialog.cancel()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog()
        state = L7TaskProgress(false, "done", completed = 1, total = 1, result = true)
        tick()
        assertFalse(confirmation.isShowing); assertTrue(progress.dialog.isShowing); assertEquals(0, stopped)
        progress.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, viewed); assertFalse(progress.dialog.isShowing)
    }
}
