package com.shilapi.xcertplay

import android.content.SharedPreferences
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownPairingException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class GalaxyLockdownRecordsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
    private val records get() = GalaxyLockdownRecords(prefs)
    private val a get() = record(1)
    private val b get() = record(2)
    private fun record(key: Int) = LockdownPairRecord.restore("host$key", "buid$key", "00:00:00:00:00:01",
        byteArrayOf(key.toByte()), byteArrayOf(10), byteArrayOf(11), byteArrayOf(12), byteArrayOf(13), byteArrayOf(14))
    @Before fun reset() { records.clear(null); prefs.edit().clear().commit() }

    @Test fun twoPhonesSurviveAlternatingReadsAndNewRepositoryInstances() {
        records.save(a); records.save(b)
        repeat(3) {
            assertEquals("host1", records.load(a.devicePublicKeyPem)?.hostId)
            assertEquals("host2", records.load(b.devicePublicKeyPem)?.hostId)
        }
    }

    @Test fun rejectedPhoneCleanupKeepsTheOtherPhoneAndOtherSettings() {
        prefs.edit().putString("ordinary_setting", "keep").commit()
        records.save(a); records.save(b); records.clear(a.devicePublicKeyPem)
        assertNull(records.load(a.devicePublicKeyPem))
        assertEquals("host2", records.load(b.devicePublicKeyPem)?.hostId)
        assertEquals("keep", prefs.getString("ordinary_setting", null))
    }

    private fun legacy() {
        records.save(a)
        val edit = prefs.edit()
        prefs.all.forEach { (key, value) -> edit.putString(key.substringAfterLast('.'), value as String); edit.remove(key) }
        edit.commit()
    }

    @Test fun legacyMigratesOnlyWhenItsPhonePublicKeyMatches() {
        legacy()
        assertNull(records.load(b.devicePublicKeyPem))
        assertTrue(prefs.contains("lockdown_host_id"))
        assertEquals("host1", records.load(a.devicePublicKeyPem)?.hostId)
        assertFalse(prefs.contains("lockdown_host_id"))
        assertEquals("host1", records.load(a.devicePublicKeyPem)?.hostId)
    }

    @Test fun wrongStoredPublicKeyAndMalformedFieldsCannotBeReused() {
        records.save(a)
        val key = prefs.all.keys.single { it.endsWith("lockdown_device_public") }
        prefs.edit().putString(key, "02").commit()
        assertNull(records.load(a.devicePublicKeyPem))
        prefs.edit().putString(key, "not-hex").commit()
        assertNull(records.load(a.devicePublicKeyPem))
        prefs.edit().putInt(key, 1).commit()
        assertNull(records.load(a.devicePublicKeyPem))
    }

    @Test fun clearingAllRecordsAlsoRemovesLegacyButKeepsNonPairingSettings() {
        legacy(); records.save(b)
        prefs.edit().putBoolean("keep", true).commit()
        records.clear(null)
        assertNull(records.load(null)); assertNull(records.load(a.devicePublicKeyPem)); assertNull(records.load(b.devicePublicKeyPem))
        assertEquals(setOf("keep"), prefs.all.keys)
    }

    private fun failingStore(): GalaxyLockdownRecords {
        val failing = mock(SharedPreferences::class.java) { call ->
            if (call.method.name == "edit") {
                val delegate = prefs.edit()
                mock(SharedPreferences.Editor::class.java) { edit ->
                    if (edit.method.name == "commit") { delegate.apply(); false }
                    else edit.method.invoke(delegate, *edit.arguments)
                }
            } else call.method.invoke(prefs, *call.arguments)
        }
        return GalaxyLockdownRecords(failing)
    }

    @Test fun failedSaveCannotBeReusedFromThePreferencesMemoryView() {
        val broken = failingStore()
        try { broken.save(a); fail() } catch (_: LockdownPairingException.PersistenceFailed) { }
        assertTrue(prefs.all.isNotEmpty())
        assertNull(broken.load(a.devicePublicKeyPem))
    }

    @Test fun failedMigrationKeepsTheLegacyRecordAndDoesNotReportSuccess() {
        legacy()
        val broken = failingStore()
        try { broken.load(a.devicePublicKeyPem); fail() } catch (_: LockdownPairingException.PersistenceFailed) { }
        assertTrue(prefs.contains("lockdown_host_id"))
        assertNull(broken.load(a.devicePublicKeyPem))
    }

    @Test fun failedPhoneClearBlocksOnlyThatPhone() {
        records.save(a); records.save(b)
        val broken = failingStore()
        try { broken.clear(a.devicePublicKeyPem); fail() } catch (_: LockdownPairingException.PersistenceFailed) { }
        assertNull(broken.load(a.devicePublicKeyPem))
        assertEquals("host2", broken.load(b.devicePublicKeyPem)?.hostId)
    }
}
