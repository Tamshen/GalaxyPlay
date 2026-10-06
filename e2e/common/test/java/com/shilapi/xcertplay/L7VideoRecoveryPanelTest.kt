package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class L7VideoRecoveryPanelTest {
    @Test fun failureOffersExplicitRetryAndStaysVisibleUntilPresentationRecovers() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var retries = 0
        var settings = 0
        val panel = L7VideoRecoveryPanel(activity, retry = { retries++; true }, settings = { settings++ })
        activity.setContentView(panel)
        val retry = action(panel, activity.getString(R.string.l7_video_retry))
        retry.performClick()
        assertEquals(0, retries)
        panel.failed()
        assertEquals(View.VISIBLE, panel.visibility)
        retry.performClick()
        assertEquals(1, retries)
        assertEquals(0, settings)
        assertEquals(View.VISIBLE, panel.visibility)
        assertTrue(texts(panel).contains(activity.getString(R.string.l7_video_retrying)))
        action(panel, activity.getString(R.string.l7_video_settings)).performClick()
        assertEquals(1, settings)
        panel.recovered()
        retry.performClick()
        assertEquals(1, retries)
        assertEquals(View.GONE, panel.visibility)
        activity.finish()
    }

    @Test fun rejectedRetryShowsAnActionableStateInsteadOfClaimingSuccess() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val panel = L7VideoRecoveryPanel(activity, retry = { false }, settings = {})
        panel.failed()
        action(panel, activity.getString(R.string.l7_video_retry)).performClick()
        assertTrue(texts(panel).contains(activity.getString(R.string.l7_video_retry_unavailable)))
        assertEquals(View.VISIBLE, panel.visibility)
        activity.finish()
    }

    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = nodes(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun action(root: View, title: String): View {
        var target: View = nodes(root).filterIsInstance<TextView>().single { it.text.toString() == title }
        while (!target.isClickable) target = target.parent as View
        return target
    }
}
