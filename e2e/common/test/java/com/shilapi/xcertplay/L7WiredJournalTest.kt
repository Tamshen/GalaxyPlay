package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7WiredJournalTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("wired_test", Context.MODE_PRIVATE)
    private var wall = 1_000L
    private var mono = 100L
    private fun journal() = L7WiredJournal(prefs, { wall }, { mono })
    @Before fun before() { prefs.edit().clear().commit() }

    @Test fun manualCrashesKeepTwoIndependentAttemptsAndNeverStoreExceptionMessages() {
        assertFalse(prefs.getBoolean("auto_connect", false))
        val first = journal().begin(10, "test")
        journal().event(first, "VPN_LAUNCH", "BEGIN")
        val error = IllegalStateException("ssid=secret token=private")
        error.stackTrace = arrayOf(StackTraceElement("com.shilapi.xcertplay.Test", "open", "Test.kt", 9),
            StackTraceElement("private.vendor.Identifier", "secret", "secret", 1))
        journal().crash(10, error)
        wall += 100; mono += 100
        val second = journal().begin(20, "test")
        journal().event(second, "VPN_PREPARE", "BEGIN")
        journal().crash(20, error)
        val report = journal().report().joinToString("\n")
        assertTrue(report.contains("attempt=$first")); assertTrue(report.contains("attempt=$second"))
        assertEquals(2, report.lines().count { it.contains("outcome=JAVA_CRASH") })
        assertTrue(report.contains("failurePhase=VPN_LAUNCH"))
        assertTrue(report.contains("com.shilapi.xcertplay.Test.open:9"))
        assertFalse(prefs.all.toString().contains("secret"))
        assertFalse(prefs.all.toString().contains("private.vendor"))
        assertTrue(journal().notice())
    }

    @Test fun newProcessAssociatesOnlyItsPredecessorAndLeavesMissingEvidenceUnknown() {
        journal().begin(10, "test")
        journal().recover(20) { pid, since -> assertEquals(10, pid); assertEquals(1000L, since); 5 }
        assertTrue(journal().report().first().contains("outcome=NATIVE_CRASH"))
        assertTrue(journal().notice())
        journal().acknowledge()
        journal().begin(20, "test")
        journal().recover(30) { _, _ -> null }
        assertTrue(journal().report().any { it.contains("outcome=INTERRUPTED") && it.contains("exitReason=-1") })
    }

    @Test fun explicitCancellationAndKnownSystemReclaimDoNotBecomeCrashNotices() {
        val id = journal().begin(10, "test")
        journal().event(id, "STOP", "EXPLICIT", outcome = "CANCELLED")
        journal().recover(20) { _, _ -> fail("cancelled attempt must not query exits"); 5 }
        assertFalse(journal().notice())
        journal().begin(20, "test")
        journal().recover(30) { _, _ -> 3 }
        assertFalse(journal().notice())
    }

    @Test fun closingTheFailedPageDoesNotOverwriteTheFailure() {
        val id = journal().begin(10, "test")
        journal().event(id, "VPN_LAUNCH", "FAILED", ActivityFailure(), "FAILED")
        journal().event(id, "HOST", "CLOSED", outcome = "CANCELLED")
        assertTrue(journal().report().first().contains("outcome=FAILED"))
        assertTrue(journal().report().first().contains("failurePhase=VPN_LAUNCH"))
    }

    @Test fun lateEventsCannotOverwriteTheNewAttemptAndEvictionIsExplicit() {
        val old = journal().begin(10, "test")
        val current = journal().begin(10, "test")
        journal().event(old, "VPN_LAUNCH", "FAILED", outcome = "FAILED")
        repeat(70) { journal().event(current, "TRANSPORT", "STAGE_$it") }
        val lines = journal().report().filter { it.contains("attempt=$current") }
        assertEquals(25, lines.size)
        assertTrue(lines.first().contains("omittedEvents=47"))
        assertTrue(lines[1].contains("phase=HOST result=BEGIN"))
        assertTrue(lines.last().contains("STAGE_69"))
        assertFalse(lines.any { it.contains("VPN_LAUNCH") })
    }

    @Test fun clearingDiagnosticsCannotBeUndoneByALateEvent() {
        val old = journal().begin(10, "test")
        journal().clear()
        journal().event(old, "VPN", "FAILED")
        assertTrue(journal().report().isEmpty())
        assertFalse(journal().notice())
    }

    @Test fun boundedLongStackFramesSurviveReloadForBothAttempts() {
        val error = RuntimeException("private message")
        error.stackTrace = Array(8) {
            StackTraceElement("com.shilapi." + "A".repeat(120), "method" + "B".repeat(120), "Test.kt", it)
        }
        repeat(2) { attempt ->
            val id = journal().begin(attempt + 10, "test")
            repeat(30) { journal().event(id, "VPN_LAUNCH", "FAILED", error) }
        }
        assertEquals(2, journal().report().count { "startedAt=" in it })
        assertTrue(prefs.getString("attempts", "").orEmpty().length < 128 * 1024)
        assertFalse(prefs.all.toString().contains("private message"))
    }

    private class ActivityFailure : RuntimeException()
}
