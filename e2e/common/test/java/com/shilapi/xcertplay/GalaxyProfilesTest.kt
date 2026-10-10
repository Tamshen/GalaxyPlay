package com.shilapi.xcertplay

import android.content.Context
import android.util.AtomicFile
import com.shilapi.xcertplay.media.AudioOutputRole
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyProfilesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val repository get() = GalaxyProfiles(app)
    @Before fun reset() {
        File(app.filesDir, "configurations").deleteRecursively()
        GalaxyConfigurationFields.names.forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        L7AudioTemplates.Model.entries.forEach { AtomicFile(File(app.filesDir, GalaxyConfigurationFields.audioFile(it.id))).delete() }
    }
    @Test fun migrationCapturesOneCompleteCurrentConfigurationWithoutConfirmingModel() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().putInt("display_scale_tenths", 8).putInt("media_buffer_ms", 500)
            .putString("remote_mfi_token", "synthetic_token").putString("identity_private", "protected_identity")
            .putStringSet("pairing_ids", setOf("synthetic_pair")).commit()
        app.getSharedPreferences("l7_authentication", 0).edit().putString("source", "TEXT").commit()
        val current = repository.active()
        val values = current.configuration.preferences.getValue("xcertplay_airplay")
        assertEquals(80, values["display_scale_percent"])
        assertEquals(500, values["media_buffer_ms"])
        assertEquals("synthetic_token", values["remote_mfi_token"])
        assertFalse(values.containsKey("identity_private"))
        assertFalse(values.containsKey("pairing_ids"))
        assertFalse(current.configuration.preferences.getValue("l7_audio_templates").containsKey("model"))
        assertEquals(3, current.configuration.audio.size)
        assertEquals(setOf("current"), repository.list().map { it.id }.toSet())
        repository.applyTemplate("l6")
        assertEquals("protected_identity", prefs.getString("identity_private", null))
        assertEquals(setOf("synthetic_pair"), prefs.getStringSet("pairing_ids", null))
        assertEquals("TEXT", app.getSharedPreferences("l7_authentication", 0).getString("source", null))
    }
    @Test fun vehicleTemplatesAndEditsNeverReplaceApplicationPreferences() {
        AppLocale.save(app, "en")
        L7UiDensity.save(app, 320)
        app.getSharedPreferences("l7_floating_navigation", 0).edit().putFloat("x", .7f).putInt("transparency", 75).commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().putString("carplay_night_mode", "night").putBoolean("debug_logs_enabled", true).commit()
        app.getSharedPreferences("l7_remote_log", 0).edit().putString("endpoint", "https://synthetic.example/api/test").putString("authorization", "Basic synthetic").commit()
        val expected = GalaxyApplicationPreferences.capture(app).toString()
        for (model in listOf("l6", "l7")) {
            val applied = repository.applyTemplate(model)
            val draft = GalaxyConfigurationContext(app, applied, true)
            AirPlayPersistence.saveFps(draft, 60)
            repository.save(applied.copy(configuration = draft.configuration()))
            repository.restore()
            assertEquals(expected, GalaxyApplicationPreferences.capture(app).toString())
            assertFalse(repository.active().configuration.preferences.values.any { it.containsKey("app_language") || it.containsKey("density") })
            assertFalse(repository.active().configuration.preferences.getValue("xcertplay_airplay").containsKey("carplay_night_mode"))
            assertEquals(60, AirPlayPersistence.loadFps(app))
        }
    }
    @Test fun upgradingBundledConfigurationPreservesLiveAppPreferencesAndRemovesBundledValues() {
        val current = repository.active()
        val preferences = current.configuration.preferences.toMutableMap()
        preferences["diplay"] = preferences.getValue("diplay") + ("app_language" to "zh")
        preferences["l7_ui"] = mapOf("density" to 240)
        File(app.filesDir, "configurations/current.json").writeText(current.copy(configuration = current.configuration.copy(preferences = preferences)).json().toString())
        AppLocale.save(app, "en"); L7UiDensity.save(app, 320)
        repository.restore()
        val upgraded = repository.active()
        assertEquals(current.id, upgraded.id)
        assertEquals(current.revision + 1, upgraded.revision)
        assertEquals("en", AppLocale.preference(app)); assertEquals(320, L7UiDensity.value(app))
        assertFalse(upgraded.configuration.preferences.getValue("diplay").containsKey("app_language"))
        assertTrue(upgraded.configuration.preferences.getValue("l7_ui").isEmpty())
        assertTrue(File(app.filesDir, "configurations/current.json.previous").isFile)
    }
    @Test fun independentAppChangesDoNotReviseVehicleFileAndRemainVisibleToFrozenContext() {
        val saved = repository.applyTemplate("l7")
        val frozen = GalaxyConfigurationContext(app, saved)
        AppLocale.save(app, "zh"); L7UiDensity.save(app, 240)
        assertEquals(saved, repository.refresh())
        assertEquals("zh", AppLocale.preference(frozen))
        assertEquals(240, L7UiDensity.value(frozen))
        assertEquals(saved.configuration, frozen.configuration())
    }
    @Test fun draftDoesNotWritePreferencesOrFilesAndSavedInactiveFileDoesNotSwitch() {
        val initial = repository.active()
        val original = File(app.filesDir, "configurations/current.json").readText()
        val draft = GalaxyConfigurationContext(app, repository.draft("l6", "L6 test"), true)
        draft.getSharedPreferences("xcertplay_airplay", 0).edit().putInt("media_buffer_ms", 1000).commit()
        L7AudioTemplates.saveCustom(draft, L7AudioTemplates.load(draft).withChoice(AudioOutputRole.NAVIGATION, 19))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(app))
        assertFalse(File(app.filesDir, "audio-template-l6.json").exists())
        assertEquals(original, File(app.filesDir, "configurations/current.json").readText())
        val saved = repository.save(draft.profile.copy(configuration = draft.configuration()))
        assertEquals(initial.id, repository.active().id)
        assertEquals(1, saved.revision)
        repository.select(saved.id)
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(app))
        assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(app))
        assertEquals(19, L7AudioTemplates.load(app).choice(AudioOutputRole.NAVIGATION))
    }
    @Test fun frozenContextKeepsOriginalSettingsAndSharesPairingStorageAfterSwitch() {
        val saved = repository.applyTemplate("l7")
        val frozen = GalaxyConfigurationContext(app, saved)
        val identity = AirPlayPersistence.loadIdentity(app)
        repository.applyTemplate("l6")
        assertEquals(L7AudioTemplates.Model.L7, L7AudioTemplates.model(frozen))
        assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(app))
        assertEquals(identity.pairingId, AirPlayPersistence.loadIdentity(frozen).pairingId)
        AirPlayPersistence.savePairing(frozen, "test_peer", byteArrayOf(1, 2, 3))
        assertTrue(app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .getStringSet("pairing_ids", emptySet())!!.contains("test_peer"))
        assertEquals(saved.configuration.json().toString(), frozen.configuration().json().toString())
    }
    @Test fun sameModelFilesStayIndependentAndStaleEditorCannotOverwriteARevision() {
        repository.active()
        val first = repository.save(repository.draft("l7", "First"))
        val second = repository.save(repository.draft("l7", "Second"))
        val changed = GalaxyConfigurationContext(app, first, true)
        changed.getSharedPreferences("xcertplay_airplay", 0).edit().putInt("display_fps", 60).commit()
        val next = repository.save(first.copy(configuration = changed.configuration()))
        assertEquals(2, next.revision)
        assertTrue(runCatching { repository.save(first.copy(name = "Stale")) }.isFailure)
        assertEquals(30, repository.list().first { it.id == second.id }.configuration.preferences
            .getValue("xcertplay_airplay")["display_fps"])
        assertEquals(next, repository.list().first { it.id == first.id })
    }
    @Test fun corruptFileRecoversPreviousValidConfigurationAndMissingRecoveryFails() {
        val old = repository.active()
        repository.save(old.copy(name = "Updated"))
        File(app.filesDir, "configurations/current.json").writeText("broken")
        assertEquals(old, repository.active())
        File(app.filesDir, "configurations/current.json").writeText("broken")
        File(app.filesDir, "configurations/current.json.previous").delete()
        assertTrue(runCatching { repository.active() }.isFailure)
        assertEquals("broken", File(app.filesDir, "configurations/current.json").readText())
    }
    @Test fun failedWriteAndInvalidParametersKeepOriginalFileAndCompatibilityValues() {
        val old = repository.active()
        val before = File(app.filesDir, "configurations/current.json").readText()
        val failed = GalaxyProfiles(app) { target, text ->
            if (target.baseFile.name == "current.json") throw java.io.IOException("synthetic disk failure")
            GalaxyProfiles.write(target, text)
        }
        assertTrue(runCatching { failed.save(old.copy(name = "Cannot write")) }.isFailure)
        assertTrue(runCatching { failed.applyTemplate("l6") }.isFailure)
        assertEquals(before, File(app.filesDir, "configurations/current.json").readText())
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(app))
        val invalid = GalaxyConfigurationContext(app, old, true)
        invalid.getSharedPreferences("xcertplay_airplay", 0).edit().putInt("display_fps", 300).commit()
        assertTrue(runCatching { repository.save(old.copy(configuration = invalid.configuration())) }.isFailure)
        assertEquals(before, File(app.filesDir, "configurations/current.json").readText())
    }
    @Test fun unreadableLegacyTemplateIsPreservedAndExplicitReplacementKeepsRecoveryCopy() {
        val raw = File(app.filesDir, "audio-template-l6.json").apply { parentFile!!.mkdirs(); writeText("broken template") }
        val migrated = repository.active()
        assertTrue("l6" in migrated.configuration.audioRecovery)
        repository.restore()
        assertEquals("broken template", raw.readText())
        val changed = GalaxyConfigurationContext(app, migrated, true)
        L7AudioTemplates.selectModel(changed, L7AudioTemplates.Model.L6)
        L7AudioTemplates.saveCustom(changed, L7AudioTemplates.load(changed))
        assertFalse("l6" in changed.configuration().audioRecovery)
        repository.save(migrated.copy(configuration = changed.configuration()))
        assertEquals("broken template", File(raw.path + ".unreadable").readText())
        assertEquals(L7AudioTemplates.Model.L6, L7AudioTemplates.model(app))
    }
    @Test fun runtimeWritesSaveFuturePreferencesWithoutChangingCurrentConnectionReadValues() {
        val old = repository.applyTemplate("l7")
        val context = GalaxyConfigurationContext(app, old, runtimeOnly = true)
        AirPlayPersistence.saveMediaBufferMillis(context, 1000)
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(app))
        val next = repository.refresh()
        assertTrue(next.revision > old.revision)
        assertEquals(old.configuration.json().toString(), context.configuration().json().toString())
    }
    @Test fun corruptInactiveFileDoesNotBlockOtherFilesAndDuplicateNamesAreRejected() {
        repository.active()
        File(app.filesDir, "configurations/broken.json").writeText("broken")
        val catalog = repository.catalog()
        assertEquals(1, catalog.unavailable)
        assertEquals(1, catalog.profiles.size)
        val first = repository.save(repository.draft("l7", "Same name"))
        assertTrue(runCatching { repository.save(repository.draft("l6", "Same name")) }.exceptionOrNull() is GalaxyProfileNameConflict)
        assertEquals(first.id, repository.select(first.id).id)
    }
}
