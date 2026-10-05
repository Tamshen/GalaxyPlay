package com.shilapi.xcertplay

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7WiredUploadTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val folder get() = File(app.filesDir, "logs")
    @Before fun before() { L7WiredDiagnostics.clear(app); L7DebugLog.buffer.clear(); folder.deleteRecursively(); folder.mkdirs() }
    @After fun after() { L7WiredDiagnostics.clear(app); L7DebugLog.buffer.clear(); folder.deleteRecursively() }

    @Test fun saturatedUploadStillContainsBothCrashAttemptsAndProcessExitAvailability() {
        repeat(2) {
            val id = L7WiredDiagnostics.begin(app)
            L7WiredDiagnostics.event(app, id, "VPN_LAUNCH", "BEGIN")
            L7WiredDiagnostics.crash(app, IllegalStateException("private auth content"))
        }
        File(folder, "probe-latest.log").writeText((0..3500).joinToString("\n") {
            "L7_PROBE item=$it detail=" + "synthetic ".repeat(160)
        })
        File(folder, "diplay.log").writeText((0..3000).joinToString("\n") {
            "media event=$it detail=" + "synthetic ".repeat(160)
        })
        val report = RemoteLogReport.collect(app)
        val records = report.batches.flatMap { it.entries }
        assertTrue(records.any { it.source == "wired-startup" && "VPN_LAUNCH" in it.message })
        assertEquals(2, records.count { it.source == "wired-startup" && "outcome=JAVA_CRASH" in it.message })
        assertTrue(records.any { it.source == "process-exits" && "requiresApi=30" in it.message })
        assertTrue(report.batches.sumOf { it.body.size } <= RemoteLogReport.MAX_REPORT_BODY)
        assertFalse(records.any { "private auth content" in it.message })
    }
}
