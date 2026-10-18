package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.host.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteLogHistoryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun noSuccessfulReceiptShowsNotUploaded() {
        assertNull(RemoteLogHistory.last(app))
        assertEquals(app.getString(R.string.l7_log_never_uploaded), RemoteLogHistory.summary(app))
    }

    @Test fun completedTimeAndCountArePersistedSeparatelyFromServerSettings() {
        val entry = RemoteLogHistory.Entry(1_700_000_000_000, 325, "a".repeat(32), "18点连接失败", "head_unit_test")
        assertTrue(RemoteLogHistory.save(app, entry))
        assertEquals(entry, RemoteLogHistory.last(app))
        assertEquals(entry.time, app.getSharedPreferences("l7_log_history", 0).getLong("successful_at", 0))
        assertEquals(entry.lines, app.getSharedPreferences("l7_log_history", 0).getInt("line_count", 0))
        RemoteLogConfig.reset(app)
        RemoteLogUpload.cancel()
        assertEquals(entry, RemoteLogHistory.last(app))
        assertTrue(RemoteLogHistory.summary(app).contains("325"))
        assertTrue(RemoteLogHistory.summary(app).contains(entry.id))
        assertTrue(RemoteLogHistory.summary(app).contains(entry.remark))
        assertTrue(RemoteLogHistory.summary(app).contains(entry.stream))
    }

    @Test fun invalidDataCannotBeShownAsASuccessfulUpload() {
        app.getSharedPreferences("l7_log_history", 0).edit().putLong("successful_at", 10).putInt("line_count", 0).commit()
        assertNull(RemoteLogHistory.last(app))
        assertThrows(IllegalArgumentException::class.java) { RemoteLogHistory.save(app, RemoteLogHistory.Entry(0, 12)) }
    }

    @Test fun statusRowCopiesTheCompletePersistedReceipt() {
        val entry = RemoteLogHistory.Entry(1_700_000_000_000, 325, "a".repeat(32), "18点连接失败", "head_unit_test")
        RemoteLogHistory.save(app, entry)
        val parent = android.widget.LinearLayout(app)
        L7RemoteLogSettings(app, parent) {}
        val views = ArrayList<android.view.View>()
        parent.findViewsWithText(views, app.getString(R.string.l7_log_state), android.view.View.FIND_VIEWS_WITH_TEXT)
        val row = generateSequence(views.single()) { it.parent as? android.view.View }
            .filterIsInstance<L7SettingRow>().first()
        assertTrue(row.performClick())
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(RemoteLogHistory.summary(app), clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains(entry.id))
    }
}
