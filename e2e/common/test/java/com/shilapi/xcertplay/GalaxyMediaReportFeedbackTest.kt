package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyMediaReportFeedbackTest {
    private lateinit var activity: ComponentActivity
    private lateinit var feedback: GalaxyMediaReportFeedback
    private val logs = mutableListOf<String>()
    private val evidence = GalaxyMediaReportEvidence(100, true, { true }, logs::add)
    private val song = CarPlayNowPlaying(title = "Synthetic", playing = true, playbackKnown = true)
    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Xcertplay)
        L7Agreement.accept(activity)
        evidence.activate(); evidence.input(song, true)
        feedback = GalaxyMediaReportFeedback(activity, LinearLayout(activity))
    }
    @After fun cleanup() { feedback.close(); evidence.stop(); evidence.released() }
    private fun dialog() = ReflectionHelpers.getField<AlertDialog>(feedback, "dialog")
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun start() {
        ReflectionHelpers.getField<List<L7SettingRow>>(feedback, "rows")[1].performClick()
    }
    private fun choose(title: Int, index: Int) {
        descendants(dialog().window!!.decorView).filterIsInstance<L7SettingRow>()
            .single { it.titleView.text.toString() == activity.getString(title) }.performClick()
        val picker = ReflectionHelpers.getField<AlertDialog>(feedback, "picker")
        picker.listView.performItemClick(picker.listView.adapter.getView(index, null, picker.listView), index, index.toLong())
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun confirmationRecordsPerFieldAndComparisonWithCurrentSession() {
        start()
        assertFalse(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        choose(R.string.media_feedback_song, 1)
        choose(R.string.media_feedback_artwork, 2)
        choose(R.string.media_feedback_playback, 3)
        choose(R.string.media_feedback_comparison, 1)
        assertTrue(logs.none { "event=OBSERVATION" in it })
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val text = logs.joinToString("\n")
        for (field in listOf("session=100", "target=CLUSTER", "title=CORRECT", "cover=MISSING",
            "playback=STALE", "comparison=OTHER_WORKS")) assertTrue(field, field in text)
    }
    @Test fun cancellationAndBackgroundNeverSubmitDraftFeedback() {
        start(); choose(R.string.media_feedback_song, 1)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        start(); choose(R.string.media_feedback_song, 1)
        val pending = dialog()
        feedback.background()
        pending.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(logs.none { "event=OBSERVATION" in it })
    }
    @Test fun changedTrackAndDisconnectedSessionRejectPendingConfirmation() {
        start(); choose(R.string.media_feedback_song, 1)
        evidence.input(song.copy(title = "next"), true)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(logs.none { "event=OBSERVATION" in it })
        start(); choose(R.string.media_feedback_song, 1)
        evidence.stop()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(logs.none { "event=OBSERVATION" in it })
        feedback.update()
        assertTrue(ReflectionHelpers.getField<List<L7SettingRow>>(feedback, "rows").none { it.isEnabled })
    }
}
