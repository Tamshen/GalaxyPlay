package com.shilapi.xcertplay

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class L7StartupRecoveryTest {
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("startup_test", Context.MODE_PRIVATE)
    private fun state() = L7StartupRecovery(prefs)
    @Before fun setup() { prefs.edit().clear().putBoolean("auto_connect", true).commit() }

    @Test fun thirdUnfinishedStartStopsAutomaticConnectionAndPreservesOtherSettings() {
        prefs.edit().putString("phone_name", "test phone").putString("auth", "test fixture")
            .putString("hotspot", "test hotspot").commit()
        state().begin(10, 1, L7StartupRecovery.Exit.UNKNOWN)
        for (attempt in 1..3) {
            val next = state()
            assertTrue(next.begin(10L + attempt, attempt + 1, L7StartupRecovery.Exit.UNKNOWN))
            assertEquals(attempt, next.failures)
            assertEquals(attempt == 3, next.blocked)
            assertEquals(attempt < 3, prefs.getBoolean("auto_connect", false))
        }
        assertTrue(state().notice)
        assertEquals("test phone", prefs.getString("phone_name", null))
        assertEquals("test fixture", prefs.getString("auth", null))
        assertEquals("test hotspot", prefs.getString("hotspot", null))
    }

    @Test fun javaCrashIsCountedOnlyOnceEvenWhenSystemHistoryIsMissingOrMisclassified() {
        state().begin(1, 1, L7StartupRecovery.Exit.UNKNOWN)
        state().recordCrash(IllegalStateException("private message must not persist"))
        state().recordCrash(IllegalStateException("duplicate"))
        state().healthy() // 迟到的稳定计时不能覆盖已经记录的崩溃。
        state().begin(2, 2, L7StartupRecovery.Exit.CLEAN)
        assertEquals(1, state().failures)
        assertFalse(prefs.all.toString().contains("private message"))
    }

    @Test fun confirmedForceStopOrMemoryReclaimDoesNotCountAsCrash() {
        state().begin(1, 1, L7StartupRecovery.Exit.UNKNOWN)
        state().begin(2, 2, L7StartupRecovery.Exit.CRASH)
        state().begin(3, 3, L7StartupRecovery.Exit.CLEAN)
        assertEquals(0, state().failures)
        assertFalse(state().blocked)
    }

    @Test fun stableSessionAndExplicitStopResetConsecutiveFailures() {
        state().begin(1, 1, L7StartupRecovery.Exit.UNKNOWN)
        state().begin(2, 2, L7StartupRecovery.Exit.CRASH)
        state().healthy()
        assertFalse(state().pending)
        assertEquals(0, state().failures)
        assertTrue(state().arm(3, 3))
        state().begin(4, 4, L7StartupRecovery.Exit.CRASH)
        assertEquals(1, state().failures)
    }

    @Test fun disabledAutomaticConnectionDoesNotArmOrAccumulate() {
        state().setEnabled(false, 1, 1)
        state().arm(2, 2)
        state().recordCrash(RuntimeException())
        state().begin(3, 3, L7StartupRecovery.Exit.CRASH)
        assertEquals(0, state().failures)
        assertFalse(state().pending)
    }

    @Test fun recoveryRemainsBlockedUntilUserReenablesAutoConnect() {
        state().begin(1, 1, L7StartupRecovery.Exit.UNKNOWN)
        repeat(3) { state().begin(it + 2L, it + 2, L7StartupRecovery.Exit.CRASH) }
        state().healthy()
        state().acknowledge()
        state().begin(8, 8, L7StartupRecovery.Exit.CLEAN)
        assertTrue(state().blocked)
        assertFalse(state().notice)
        assertFalse(state().pending)
        state().setEnabled(true, 9, 9)
        assertFalse(state().blocked)
        assertEquals(0, state().failures)
        assertTrue(state().pending)
    }

    @Test fun exitReasonsDistinguishNativeCrashFromNormalProcessTermination() {
        for (reason in listOf(4, 5, 6, 7)) assertEquals(L7StartupRecovery.Exit.CRASH, L7StartupRecovery.classify(reason, 0))
        for (signal in listOf(6, 7, 8, 11)) assertEquals(L7StartupRecovery.Exit.CRASH, L7StartupRecovery.classify(2, signal))
        for (reason in listOf(1, 3, 8, 9, 10, 11, 12)) assertEquals(L7StartupRecovery.Exit.CLEAN, L7StartupRecovery.classify(reason, 0))
        assertEquals(L7StartupRecovery.Exit.CLEAN, L7StartupRecovery.classify(2, 9))
        assertEquals(L7StartupRecovery.Exit.UNKNOWN, L7StartupRecovery.classify(13, 0))
    }

    @Test fun uncaughtExceptionStillReachesAndroidWhenRecordingFails() {
        val error = IllegalStateException("test")
        var delegated: Throwable? = null
        L7CrashRecorder(Thread.UncaughtExceptionHandler { _, e -> delegated = e }) {
            throw IllegalStateException("storage unavailable")
        }.uncaughtException(Thread.currentThread(), error)
        assertSame(error, delegated)
    }
}
