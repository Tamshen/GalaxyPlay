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
        val entry = RemoteLogHistory.Entry(1_700_000_000_000, 325)
        assertTrue(RemoteLogHistory.save(app, entry))
        assertEquals(entry, RemoteLogHistory.last(app))
        assertEquals(entry.time, app.getSharedPreferences("l7_log_history", 0).getLong("successful_at", 0))
        assertEquals(entry.lines, app.getSharedPreferences("l7_log_history", 0).getInt("line_count", 0))
        RemoteLogConfig.reset(app)
        RemoteLogUpload.cancel()
        assertEquals(entry, RemoteLogHistory.last(app))
        assertTrue(RemoteLogHistory.summary(app).contains("325"))
    }

    @Test fun invalidDataCannotBeShownAsASuccessfulUpload() {
        app.getSharedPreferences("l7_log_history", 0).edit().putLong("successful_at", 10).putInt("line_count", 0).commit()
        assertNull(RemoteLogHistory.last(app))
        assertThrows(IllegalArgumentException::class.java) { RemoteLogHistory.save(app, RemoteLogHistory.Entry(0, 12)) }
    }
}
