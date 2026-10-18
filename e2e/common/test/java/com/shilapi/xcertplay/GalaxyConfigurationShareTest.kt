package com.shilapi.xcertplay

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
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
class GalaxyConfigurationShareTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val repository get() = GalaxyProfiles(app)
    @Before fun reset() {
        File(app.filesDir, "configurations").deleteRecursively()
        GalaxyConfigurationFields.names.forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        app.getSharedPreferences("galaxy_configuration_sharing", 0).edit().clear().commit()
    }

    @Test fun completeVehicleParametersAndNameRoundTripThroughJsonAndCopiedCode() {
        val profile = repository.applyTemplate("l7_no_headrest")
        val share = GalaxyConfigurationShare.from(profile, "家用 L7 配置")
        assertEquals(share, GalaxyConfigurationShare.parse(share.json()))
        assertEquals(share, GalaxyConfigurationShare.parse("  ${share.code()}\n"))
        assertEquals(profile.configuration, share.configuration)
        assertTrue(share.code().length <= GalaxyConfigurationQr.MAX_CODE_LENGTH)
        assertEquals(app.getString(com.shilapi.xcertplay.host.R.string.config_share_default_name, 1), GalaxyConfigurationNames.next(app))
        GalaxyConfigurationNames.used(app)
        assertEquals(app.getString(com.shilapi.xcertplay.host.R.string.config_share_default_name, 2), GalaxyConfigurationNames.next(app))
    }

    @Test fun automaticNamesFollowTheDisplayLanguageAndCustomNamesRemainUnchanged() {
        fun localized(language: String): android.content.Context = app.createConfigurationContext(
            android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) })
        val chinese = localized("zh-CN")
        val english = localized("en")
        val profile = repository.applyTemplate("l7")
        assertEquals("配置07", GalaxyConfigurationNames.display(chinese, "Profile 07"))
        assertEquals("Profile 07", GalaxyConfigurationNames.display(english, "配置07"))
        assertEquals("配置01", GalaxyConfigurationNames.display(chinese, "Existing configuration"))
        assertEquals("Profile 01", GalaxyConfigurationNames.display(english, "原有配置"))
        assertEquals("配置01", GalaxyConfigurationNames.forShare(chinese, profile.copy(name = "Existing configuration")))
        GalaxyConfigurationNames.used(app)
        assertEquals("Profile 02", GalaxyConfigurationNames.forShare(english, profile.copy(name = "原有配置")))
        for (name in listOf("家用 L7", "My L7", "Profile for work")) {
            assertEquals(name, GalaxyConfigurationNames.display(chinese, name))
            assertEquals(name, GalaxyConfigurationNames.forShare(english, profile.copy(name = name)))
        }
        assertEquals(profile, repository.active())
    }

    private fun legacyCode(value: GalaxyConfigurationShare): String {
        val packed = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(packed).use { it.write(value.json().toByteArray(Charsets.UTF_8)) }
        return GalaxyConfigurationShare.LEGACY_PREFIX + java.util.Base64.getUrlEncoder()
            .withoutPadding().encodeToString(packed.toByteArray())
    }

    @Test fun standardPresetsOmitUnchangedParametersAndAudioWhileRestoringTheFullSnapshot() {
        for (id in GalaxyConfigurationShareBase.ids) {
            val profile = repository.applyTemplate(id)
            val value = GalaxyConfigurationShare.from(profile, "配置01")
            // 固定基线不得随今后默认值修改；新默认值应作为差异传输。
            assertEquals(value.configuration, GalaxyConfigurationShareBase.configuration(id))
            val compact = JSONObject(GalaxyConfigurationShareCompact.encode(value))
            assertEquals(0, compact.getJSONArray("changes").length())
            assertFalse(compact.has("configuration"))
            assertFalse(compact.toString().contains("audio_templates"))
            assertTrue(value.code().length < 260)
            assertTrue(value.code().length * 3 < legacyCode(value).length)
            assertEquals(value, GalaxyConfigurationShare.parse(value.code()))
        }
    }

    @Test fun modifiedSettingsUnsetValuesMissingGroupsAndCustomAudioSurviveCompactRoundTrip() {
        val original = repository.applyTemplate("l7_no_headrest")
        val groups = original.configuration.preferences.toMutableMap()
        groups.remove("carplay_picture")
        groups["xcertplay_airplay"] = groups.getValue("xcertplay_airplay") - "main_buffered_audio" + mapOf(
            "display_scale_percent" to 73, "display_fps" to 30, "audio_focus_enabled" to null,
            "galaxy_video_decoder_l7" to "OMX", "galaxy_p2p_address_mode" to "IPV6_ONLY")
        val routing = com.shilapi.xcertplay.media.AudioRoutingTemplate.parse(original.configuration.audio.getValue("l6"))
            .withChoice(com.shilapi.xcertplay.media.AudioOutputRole.MEDIA, 102).toJson()
        val value = GalaxyConfigurationShare.from(original.copy(configuration = original.configuration.copy(
            preferences = groups, audio = original.configuration.audio + ("l6" to routing)), templateId = "custom"), "我的配置")
        assertEquals(value, GalaxyConfigurationShare.parse(value.code()))
        assertEquals(value.configuration, GalaxyConfigurationShare.parse(value.json()).configuration)
    }

    @Test fun oldQrCodesAndFullJsonFilesRemainImportable() {
        val value = GalaxyConfigurationShare.from(repository.applyTemplate("l7"), "旧版配置")
        assertEquals(1, JSONObject(value.json()).getInt("version"))
        assertEquals(value, GalaxyConfigurationShare.parse(legacyCode(value)))
        assertEquals(value, GalaxyConfigurationShare.parse(value.json()))
    }

    @Test fun unknownBaselinesAndInjectedPreferencePathsAreRejectedWithoutChangingTheVehicle() {
        val profile = repository.applyTemplate("l7")
        val value = GalaxyConfigurationShare.from(profile, "配置01")
        val encoded = GalaxyConfigurationShareCompact.encode(value)
        val badBase = JSONObject(encoded).put("baseline", 99)
        val injected = JSONObject(encoded).put("changes", org.json.JSONArray().put(org.json.JSONArray()
            .put(org.json.JSONArray(listOf("preferences", "xcertplay_airplay", "remote_mfi_token")))
            .put(JSONObject().put("type", "string").put("value", "synthetic-token"))))
        for (data in listOf(badBase, injected)) {
            assertTrue(runCatching { GalaxyConfigurationShare.parse(data.toString()) }.isFailure)
            assertEquals(profile, repository.active())
        }
    }

    @Test fun sharingCannotIncludeConnectionOrAuthenticationMaterial() {
        val profile = repository.applyTemplate("l7")
        val groups = profile.configuration.preferences.toMutableMap()
        groups["xcertplay_airplay"] = groups.getValue("xcertplay_airplay") +
            mapOf("manual_hotspot_passphrase" to "synthetic-secret", "remote_mfi_token" to "synthetic-token")
        val share = GalaxyConfigurationShare.from(profile.copy(configuration = profile.configuration.copy(preferences = groups)), "配置01")
        assertFalse(share.json().contains("synthetic-secret"))
        assertFalse(share.json().contains("synthetic-token"))
        val injected = JSONObject(share.json())
        injected.getJSONObject("configuration").getJSONObject("preferences").getJSONObject("xcertplay_airplay")
            .put("remote_mfi_token", JSONObject().put("type", "string").put("value", "synthetic-token"))
        assertTrue(runCatching { GalaxyConfigurationShare.parse(injected.toString()) }.isFailure)
    }

    @Test fun invalidVersionTruncatedCodeAndOversizedInputNeverReplaceCurrentConfiguration() {
        val current = repository.applyTemplate("l7")
        val share = GalaxyConfigurationShare.from(current, "配置01")
        for (text in listOf("garbage", share.code().dropLast(8),
            JSONObject(share.json()).put("version", 99).toString(), "x".repeat(GalaxyConfigurationShare.MAX_TEXT + 1))) {
            assertTrue(runCatching { GalaxyConfigurationShare.parse(text) }.isFailure)
            assertEquals(current, repository.active())
        }
    }

    @Test fun compressedInputCannotExpandPastTheConfigurationBudget() {
        val packed = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(packed).use { it.write(ByteArray(GalaxyConfiguration.MAX_BYTES + 1) { 65 }) }
        val code = GalaxyConfigurationShare.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray())
        assertTrue(runCatching { GalaxyConfigurationShare.parse(code) }.isFailure)
    }

    @Test fun importRequiresTheSameRevisionAndKeepsThePreviousSessionAndExternalPreferences() {
        val current = repository.applyTemplate("l7")
        val frozen = GalaxyConfigurationContext(app, current, runtimeOnly = true)
        AirPlayPersistence.saveManualHotspotSsid(app, "synthetic-network")
        val shared = GalaxyConfigurationShare.from(current.copy(configuration =
            GalaxyVehicleTemplates.find("l7_no_headrest")!!.create(app), templateId = "l7_no_headrest"), "朋友的配置")
        val imported = repository.importShared(current, shared)
        assertEquals(current.id, imported.id)
        assertEquals("朋友的配置", imported.name)
        assertEquals("l7_no_headrest", imported.template)
        assertEquals(false, frozen.getSharedPreferences("xcertplay_airplay", 0).getBoolean("audio_focus_enabled", true))
        assertEquals(true, app.getSharedPreferences("xcertplay_airplay", 0).getBoolean("audio_focus_enabled", false))
        assertEquals("synthetic-network", AirPlayPersistence.loadManualHotspotSsid(app))
        assertTrue(runCatching { repository.importShared(current, shared) }.isFailure)
        assertEquals(imported, repository.active())
    }

    @Test fun generatedQrContainsTheCompletePasteCodeWithAReadableQuietZone() {
        val share = GalaxyConfigurationShare.from(repository.applyTemplate("l7_no_headrest"), "配置01")
        val code = share.code()
        val bitmap = GalaxyConfigurationQr.encode(code)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(0, 0))
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
            assertEquals(code, decoded)
            assertEquals(share, GalaxyConfigurationShare.parse(decoded))
        } finally { bitmap.recycle() }
    }
}
