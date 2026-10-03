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
    @Test fun exportingBlocksRepeatedRequestsAndRestoresEntryAfterCompletion() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = LinearLayout(activity)
        var exports = 0
        val settings = L7DiagnosticSettings(activity, content, {}, { exports++ }, {}, {})
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
