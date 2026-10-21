package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyUsbVerificationPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun previousSelectionMigratesOnceAndDoesNotOverwriteANewChoice() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().putBoolean("galaxy_legacy_usb_experimental", true).commit()
        assertTrue(GalaxyUsbVerificationPreferences.enabled(context))
        assertFalse(prefs.contains("galaxy_legacy_usb_experimental"))
        prefs.edit().putBoolean(GalaxyUsbVerificationPreferences.KEY, false).commit()
        assertFalse(GalaxyUsbVerificationPreferences.enabled(context))
    }

    @Test fun pendingRecoveryMovesBeforeTheOldJournalIsCleared() {
        val old = context.getSharedPreferences("galaxy_legacy_usb_recovery", 0)
        old.edit().putString("original", "diag,adb").putString("controller", "a600000.dwc3")
            .putString("carplay", "0").commit()
        val journal = GalaxyUsbVerificationPreferences.journal(context)
        assertEquals("diag,adb", journal.getString("original", null))
        assertEquals("a600000.dwc3", journal.getString("controller", null))
        assertEquals("0", journal.getString("carplay", null))
        assertFalse(old.contains("original"))
        assertTrue(GalaxyUsbVerificationSession.needsRecovery(context))
        journal.edit().clear().commit()
        assertFalse(GalaxyUsbVerificationSession.needsRecovery(context))
    }

    @Test fun conflictingRecoveryRecordsRemainIntactAndBlockAConnection() {
        val old = context.getSharedPreferences("galaxy_legacy_usb_recovery", 0)
        old.edit().putString("original", "diag,adb").commit()
        val current = context.getSharedPreferences(GalaxyUsbVerificationPreferences.JOURNAL, 0)
        current.edit().putString("original", "adb").commit()
        try {
            GalaxyUsbVerificationPreferences.journal(context)
            fail("应保留冲突记录并要求恢复")
        } catch (failure: GalaxyUsbVerificationFailure) {
            assertEquals(GalaxyUsbVerificationFailure.Reason.RESTORE_REQUIRED, failure.reason)
        }
        assertEquals("diag,adb", old.getString("original", null))
        assertEquals("adb", current.getString("original", null))
        assertTrue(GalaxyUsbVerificationSession.needsRecovery(context))
    }
}
