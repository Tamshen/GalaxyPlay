package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.P2pAddressMode
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyVehiclePresetsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val repository get() = GalaxyProfiles(app)

    @Before fun reset() {
        File(app.filesDir, "configurations").deleteRecursively()
        GalaxyConfigurationFields.names.forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    @Test fun l7PresetsDifferOnlyInAudioFocusAndKeepTheActualL7Model() {
        val with = GalaxyVehicleTemplates.find("l7")!!.create(app)
        val without = GalaxyVehicleTemplates.find("l7_no_headrest")!!.create(app)
        val main = with.preferences.getValue("xcertplay_airplay")
        assertEquals(60, main["display_fps"])
        assertEquals(80, main["display_scale_percent"])
        assertEquals("IPV4_ONLY", main[GalaxyP2pAddressSettings.KEY])
        assertEquals(false, main["audio_focus_enabled"])
        val adjusted = without.preferences.toMutableMap()
        assertEquals(true, adjusted.getValue("xcertplay_airplay")["audio_focus_enabled"])
        adjusted["xcertplay_airplay"] = adjusted.getValue("xcertplay_airplay") + ("audio_focus_enabled" to false)
        assertEquals(with.preferences, adjusted)
        assertEquals(with.audio, without.audio)
        for (configuration in listOf(with, without)) {
            assertEquals("l7", configuration.preferences.getValue("l7_audio_templates")["model"])
        }
    }

    @Test fun bothPresetsRoundTripThroughFilesEvidenceAndResetWithoutTouchingConnectionSettings() {
        AirPlayPersistence.saveManualHotspotSsid(app, "synthetic-network")
        for (id in listOf("l7", "l7_no_headrest")) {
            val profile = repository.applyTemplate(id)
            assertEquals("l7", profile.model)
            assertEquals(id, profile.template)
            assertEquals(profile, GalaxyProfile.parse(profile.json().toString()))
            val evidence = GalaxyConfigurationEvidence.from(app, profile)
            assertEquals(evidence, GalaxyConfigurationEvidence.read(evidence.text))
            assertEquals(id, JSONObject(evidence.text).getString("template_id"))
            val reset = repository.resetCurrent(profile)
            assertEquals(profile.configuration, reset.configuration)
            assertEquals(id, reset.template)
            assertEquals("synthetic-network", AirPlayPersistence.loadManualHotspotSsid(app))
        }
    }

    @Test fun addressModeIsSavedWithVehicleDraftAndFrozenSessionKeepsItsOriginalMode() {
        val profile = repository.applyTemplate("l7_no_headrest")
        val frozen = GalaxyConfigurationContext(app, profile, runtimeOnly = true)
        for (mode in P2pAddressMode.entries) {
            val original = repository.active()
            val draft = GalaxyConfigurationContext(app, original, editable = true)
            draft.getSharedPreferences("xcertplay_airplay", 0).edit()
                .putString(GalaxyP2pAddressSettings.KEY, mode.name).commit()
            assertEquals(original, repository.active())
            assertEquals(mode, GalaxyP2pAddressSettings.load(draft))
            val saved = repository.save(original.copy(configuration = draft.configuration()))
            assertEquals(mode, GalaxyP2pAddressSettings.load(app))
            assertEquals(P2pAddressMode.IPV4_ONLY, GalaxyP2pAddressSettings.load(frozen))
            assertEquals(mode, GalaxyP2pAddressSettings.load(GalaxyConfigurationContext(app,
                GalaxyProfile.parse(saved.json().toString()))))
            val evidence = JSONObject(GalaxyConfigurationEvidence.from(app, saved).text)
            assertEquals(mode.name, evidence.getJSONObject("configuration").getJSONObject("preferences")
                .getJSONObject("xcertplay_airplay").getJSONObject(GalaxyP2pAddressSettings.KEY).getString("value"))
            assertFalse(GalaxyApplicationPreferences.capture(app).getJSONObject("xcertplay_airplay")
                .has(GalaxyP2pAddressSettings.KEY))
        }
    }

    @Test fun oldProfileGetsIpv4WithoutReplacingItsSavedDisplayOrFocus() {
        val profile = repository.applyTemplate("l7")
        val groups = profile.configuration.preferences.toMutableMap()
        groups["xcertplay_airplay"] = groups.getValue("xcertplay_airplay") - GalaxyP2pAddressSettings.KEY +
            mapOf("display_fps" to 30, "display_scale_percent" to 100, "audio_focus_enabled" to true)
        File(app.filesDir, "configurations/current.json").writeText(
            profile.copy(configuration = profile.configuration.copy(preferences = groups)).json().toString())
        repository.restore()
        assertEquals("l7", repository.active().template)
        assertEquals(P2pAddressMode.IPV4_ONLY, GalaxyP2pAddressSettings.load(app))
        val main = repository.active().configuration.preferences.getValue("xcertplay_airplay")
        assertEquals(30, main["display_fps"])
        assertEquals(100, main["display_scale_percent"])
        assertEquals(true, main["audio_focus_enabled"])
    }

    @Test fun invalidAddressChoiceCannotReplaceTheCurrentProfile() {
        val profile = repository.applyTemplate("l7")
        val draft = GalaxyConfigurationContext(app, profile, editable = true)
        draft.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putString(GalaxyP2pAddressSettings.KEY, "invalid").commit()
        assertTrue(runCatching { repository.save(profile.copy(configuration = draft.configuration())) }.isFailure)
        assertEquals(profile, repository.active())
    }
}
