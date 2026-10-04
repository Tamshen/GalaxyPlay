package com.shilapi.xcertplay

import android.app.Activity
import android.widget.LinearLayout
import androidx.core.view.children
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7DiagnosticSettingsTest {
    @Test fun overlayUsesOneSwitchAndDeniedStartNeverShowsItAsRunning() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LinearLayout(activity)
        var requests = 0
        val settings = L7DiagnosticSettings(activity, content, {}, {}, {}, { if (it) requests++ }, {})
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val all = descendants(content)
        val control = all.filterIsInstance<android.widget.Switch>().single()
        assertFalse(control.isChecked)
        control.performClick()
        assertEquals(1, requests)
        assertFalse("服务没有启动，开关必须回到关闭", control.isChecked)
        settings.update(false); settings.update(false)
        assertEquals(1, requests)
        val labels = all.filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertFalse(labels.contains(activity.getString(R.string.l7_debug_start)))
        assertFalse(labels.contains(activity.getString(R.string.l7_debug_stop)))
        assertFalse(labels.contains(activity.getString(R.string.l7_log_retry)))
        assertFalse(labels.contains(activity.getString(R.string.l7_log_cancel)))
    }

    @Test fun externalSwitchStateRefreshDoesNotRepeatTheUserAction() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var changes = 0
        val row = L7Components.switchRow(activity, "test", "", false) { changes++ }
        row.setSwitchChecked(true); row.setSwitchChecked(false)
        assertEquals(0, changes)
        row.performClick()
        assertEquals(1, changes)
    }

    @Test fun exportingBlocksRepeatedRequestsAndRestoresEntryAfterCompletion() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LinearLayout(activity)
        var exports = 0
        val settings = L7DiagnosticSettings(activity, content, {}, { exports++ }, {}, {}, {})
        val row = content.children.filterIsInstance<LinearLayout>()
            .flatMap { it.children }.filterIsInstance<L7SettingsCard>()
            .flatMap { it.children }.filterIsInstance<L7SettingRow>()
            .single { it.titleView.text.toString() == activity.getString(R.string.save_diagnostic_report) }
        settings.update(true)
        row.performClick()
        assertEquals(0, exports)
        assertFalse(row.isEnabled)
        assertEquals(activity.getString(R.string.save_diagnostic_report), row.titleView.text.toString())
        assertEquals(activity.getString(R.string.saving_report), row.feedbackView.text.toString())
        assertEquals(1f, row.alpha)
        settings.update(false)
        assertTrue(row.isEnabled)
        assertEquals("", row.feedbackView.text.toString())
        row.performClick()
        assertEquals(1, exports)
        settings.showExportFailure()
        settings.update(false)
        assertEquals(activity.getString(R.string.l7_report_retry), row.feedbackView.text.toString())
        assertTrue(row.isEnabled)
        settings.update(true)
        assertEquals(activity.getString(R.string.saving_report), row.feedbackView.text.toString())
    }
}
