package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7VpnConsentTest {
    private val events = mutableListOf<String>()
    private val failures = mutableListOf<L7VpnConsent.Failure>()
    private var ready = 0
    private var launches = 0
    private var granted = false
    private fun gate(prepare: () -> Intent? = { if (granted) null else Intent("test.consent") },
                     launch: (Intent) -> Unit = { launches++ }) = L7VpnConsent(
        prepare, launch,
        { phase, result, error -> events += "$phase:$result:${error?.javaClass?.simpleName}" },
        { ready++ }, failures::add,
    )

    @Test fun preparedVpnProceedsWithoutLaunchingAConsentScreen() {
        gate(prepare = { null }).request()
        assertEquals(1, ready)
        assertEquals(0, launches)
        assertTrue(failures.isEmpty())
        assertTrue(events.any { it.startsWith("VPN_PREPARE:READY") })
    }

    @Test fun missingScreenStopsAndCanBeRetriedExplicitly() {
        var missing = true
        val gate = gate(launch = { if (missing) throw ActivityNotFoundException("private firmware detail") else launches++ })
        gate.request()
        assertEquals(listOf(L7VpnConsent.Failure.PAGE_MISSING), failures)
        assertEquals(0, ready)
        assertFalse(events.joinToString().contains("private"))
        missing = false
        gate.request()
        granted = true
        gate.returned(Activity.RESULT_OK)
        assertEquals(1, ready)
        assertEquals(1, launches)
    }

    @Test fun serviceRejectionAndLaunchRejectionDoNotProceed() {
        gate(prepare = { throw SecurityException("secret") }).request()
        gate(launch = { throw SecurityException("secret") }).request()
        assertEquals(listOf(L7VpnConsent.Failure.SYSTEM_DENIED, L7VpnConsent.Failure.SYSTEM_DENIED), failures)
        assertEquals(0, ready)
        assertEquals(0, launches)
    }

    @Test fun otherErrorsKeepTheirPrepareOrLaunchStage() {
        gate(prepare = { throw IllegalStateException() }).request()
        gate(launch = { throw IllegalArgumentException() }).request()
        assertEquals(listOf(L7VpnConsent.Failure.PREPARE_FAILED, L7VpnConsent.Failure.LAUNCH_FAILED), failures)
        assertTrue(events.any { it.startsWith("VPN_PREPARE:FAILED") })
        assertTrue(events.any { it.startsWith("VPN_LAUNCH:FAILED") })
        assertEquals(0, ready)
    }

    @Test fun rejectedAndCancelledResultsStopAndDoNotDuplicateTheRequest() {
        val gate = gate()
        gate.request(); gate.request()
        assertEquals(1, launches)
        gate.returned(Activity.RESULT_CANCELED)
        gate.returned(Activity.RESULT_OK)
        assertEquals(listOf(L7VpnConsent.Failure.DECLINED), failures)
        assertEquals(0, ready)
    }

    @Test fun destroyingTheOwnerDiscardsLateConsentAndNewRequests() {
        val gate = gate()
        gate.request(); gate.dispose(); gate.returned(Activity.RESULT_OK); gate.request()
        assertEquals(1, launches)
        assertEquals(0, ready)
        assertTrue(failures.isEmpty())
    }

    @Test fun unavailableFrameworkApiIsReportedInsteadOfCrashingTheCaller() {
        gate(prepare = { throw NoSuchMethodError("private detail") }).request()
        assertEquals(listOf(L7VpnConsent.Failure.PREPARE_FAILED), failures)
        assertEquals(0, ready)
    }

    @Test fun restoringPendingConsentKeepsTheAttemptAndConsumesResultWithoutRelaunching() {
        val old = gate(); old.request()
        val state = Bundle(); old.saveWaiting(state, "synthetic-attempt")
        old.dispose()
        val restored = gate()
        val attempt = L7VpnConsent.savedAttempt(state, wireless = false)
        assertEquals("synthetic-attempt", attempt)
        restored.restoreWaiting(attempt); restored.request()
        assertEquals(1, launches)
        granted = true; restored.returned(Activity.RESULT_OK); restored.returned(Activity.RESULT_OK)
        assertEquals(1, ready)
        assertTrue(events.any { it.startsWith("VPN_RESTORE:WAITING") })
        restored.saveWaiting(state, attempt)
        assertNull(L7VpnConsent.savedAttempt(state, wireless = false))
    }

    @Test fun successfulResultWithRevokedAuthorizationDoesNotStartOrRelaunch() {
        val gate = gate(); gate.request(); gate.returned(Activity.RESULT_OK)
        assertEquals(0, ready)
        assertEquals(1, launches)
        assertEquals(listOf(L7VpnConsent.Failure.PREPARE_FAILED), failures)
        assertTrue(events.any { it.startsWith("VPN_RECHECK:NOT_PREPARED") })
    }

    @Test fun restoringForWirelessOrWithoutAPendingAttemptDoesNotAcceptOldResult() {
        val old = gate(); old.request()
        val state = Bundle(); old.saveWaiting(state, "synthetic-attempt")
        val restored = gate()
        assertNull(L7VpnConsent.savedAttempt(state, wireless = true))
        restored.restoreWaiting(L7VpnConsent.savedAttempt(state, wireless = true))
        granted = true; restored.returned(Activity.RESULT_OK)
        assertEquals(0, ready)
        assertEquals(1, launches)
        assertNull(L7VpnConsent.savedAttempt(Bundle(), wireless = false))
    }

    @Test fun restoredCancellationStopsAndDisposeRejectsFurtherRestoration() {
        val restored = gate(); restored.restoreWaiting("synthetic-attempt")
        restored.returned(Activity.RESULT_CANCELED)
        assertEquals(listOf(L7VpnConsent.Failure.DECLINED), failures)
        restored.dispose(); restored.restoreWaiting("another-attempt")
        granted = true; restored.returned(Activity.RESULT_OK); restored.request()
        assertEquals(0, ready)
        assertEquals(0, launches)
    }

    @Test fun restoredGrantWithPrepareRejectionFailsInsteadOfContinuing() {
        val restored = gate(prepare = { throw SecurityException("private") })
        restored.restoreWaiting("synthetic-attempt"); restored.returned(Activity.RESULT_OK)
        assertEquals(listOf(L7VpnConsent.Failure.SYSTEM_DENIED), failures)
        assertEquals(0, ready)
        assertEquals(0, launches)
        assertFalse(events.joinToString().contains("private"))
    }
}
