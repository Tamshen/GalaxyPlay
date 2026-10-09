package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GalaxyConfigurationEvidenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        File(app.filesDir, "configurations").deleteRecursively()
        File(app.filesDir, "logs").deleteRecursively()
        GalaxyConfigurationFields.names.forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        L7DebugLog.buffer.clear()
    }
    private fun evidence(model: String = "l7", revision: Int = 1): GalaxyConfigurationEvidence {
        val configuration = GalaxyConfigurationFields.factory(app, model)
        val draft = GalaxyConfigurationContext(app, GalaxyProfile("test_$model", "Private name", revision, 1234, configuration), true)
        draft.getSharedPreferences("xcertplay_airplay", 0).edit().putString("manual_hotspot_ssid", "PRIVATE_WIFI")
            .putString("manual_hotspot_passphrase", "PRIVATE_PASSWORD").putString("remote_mfi_token", "PRIVATE_TOKEN")
            .putString("galaxy_navigation_output_L7", JSONObject().put("id", 42).put("type", 21)
                .put("name", "PRIVATE_DEVICE").put("address", "PRIVATE_ADDRESS").toString()).commit()
        return GalaxyConfigurationEvidence.from(app, draft.profile.copy(configuration = draft.configuration()))
    }
    @Test fun completeEvidenceRedactsSecretsKeepsParametersAndRoundTripsWithoutChangingId() {
        val value = evidence()
        for (secret in listOf("Private name", "PRIVATE_WIFI", "PRIVATE_PASSWORD", "PRIVATE_TOKEN", "PRIVATE_DEVICE", "PRIVATE_ADDRESS"))
            assertFalse(secret, secret in value.text)
        val data = JSONObject(value.text).getJSONObject("configuration")
        assertEquals(30, data.getJSONObject("preferences").getJSONObject("xcertplay_airplay")
            .getJSONObject("display_fps").getInt("value"))
        assertEquals(3, data.getJSONObject("audio_templates").length())
        assertEquals(value, GalaxyConfigurationEvidence.read(value.text))
        assertEquals(value.id, DiagnosticRedactor.redact("config_ref=${value.id}")!!.substringAfter('='))
        assertTrue(value.text.length > DiagnosticRedactor.MAX_LINE)
    }
    @Test fun rotationRewritesFullHeaderAndAsyncQueueKeepsCapturedConfiguration() {
        val first = evidence()
        val next = evidence("l6")
        val file = File(app.filesDir, "logs/diplay.log")
        val writer = SessionLogFile(file)
        writer.configuration = first
        AsyncDiagnosticLog.append(writer, "audio=first")
        writer.configuration = next
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        assertTrue(file.readText().contains("config_ref=${first.id}"))
        assertFalse(file.readText().contains("config_ref=${next.id}"))
        repeat(150) { writer.append("event=$it " + "x ".repeat(1900), first) }
        assertTrue(file.length() <= SessionLogFile.MAX_BYTES)
        assertTrue(file.readLines().first().startsWith(GalaxyConfigurationEvidence.PREFIX))
        val header = GalaxyConfigurationEvidence.read(file.readLines().first().removePrefix(GalaxyConfigurationEvidence.PREFIX))
        assertEquals(first.id, header.id)
        assertTrue(File(app.filesDir, "logs/previous.log").isFile)
    }
    @Test fun clippingReservesEveryReferencedHeaderAndLeavesLegacyEventsUnassociated() {
        val first = evidence()
        val second = evidence("l6")
        val lines = listOf(first.header, second.header, "legacy=unknown") + (0..4000).map {
            "config_ref=${if (it % 2 == 0) first.id else second.id} event=$it " + "detail ".repeat(100)
        }
        val report = RemoteLogReport.create(lines, "test", "test")
        val records = records(report)
        assertTrue(report.omittedLines > 0)
        assertTrue(report.batches.sumOf { it.body.size } <= RemoteLogReport.MAX_REPORT_BODY)
        val headers = records.filter { it.has("configuration_file") }
        assertEquals(setOf(first.id, second.id), headers.map { it.getString("config_id") }.toSet())
        assertEquals(records.size, records.map { it.getString("event_id") }.distinct().size)
        records.filter { it.getString("source") == "runtime" }.forEach { row ->
            assertTrue(headers.any { it.getString("config_id") == row.getString("config_id") })
        }
        val legacy = records(RemoteLogReport.create(listOf("legacy=unknown"), "test", "test"))
        assertEquals("unknown", legacy.first().getString("config_id"))
    }
    @Test fun uploadSeparatesHistoricalConfigurationFromCurrentSavedFile() {
        val old = evidence()
        val writer = SessionLogFile(File(app.filesDir, "logs/diplay.log"))
        writer.append("test=historical", old)
        val repository = GalaxyProfiles(app)
        repository.select("default_l6")
        val records = records(RemoteLogReport.collect(app))
        val historical = records.first { it.optString("configuration_role") == "at_event" }
        val saved = records.first { it.optString("configuration_role") == "saved_at_upload" }
        assertEquals(old.id, historical.getString("config_id"))
        assertNotEquals(old.id, saved.getString("config_id"))
        assertEquals("l6", saved.getJSONObject("configuration_file").getString("model"))
        assertTrue(records.any { it.getString("message").contains("test=historical") && it.getString("config_id") == old.id })
    }
    private fun records(report: RemoteLogReport): List<JSONObject> = report.batches.flatMap {
        val data = JSONArray(it.body.toString(Charsets.UTF_8))
        (0 until data.length()).map(data::getJSONObject)
    }
}
