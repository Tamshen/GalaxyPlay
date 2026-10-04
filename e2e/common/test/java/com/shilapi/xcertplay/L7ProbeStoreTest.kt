package com.shilapi.xcertplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7ProbeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun report(phase: L7ProbePhase = L7ProbePhase.COMPLETED) = L7ProbeReport(
        UUID.randomUUID().toString(), "synthetic-environment", "test", System.currentTimeMillis(), phase,
        listOf(L7ProbeItem("test", "test", "ENVIRONMENT", L7ProbeOutcome.UNKNOWN, "QUERY_FAILED", mapOf("granted" to null))), 1)

    @Test fun unknownValuesAndEvidenceContextSurviveRoundTrip() {
        val store = L7ProbeStore(temporary.root)
        val original = report()
        store.save(original)
        val restored = store.load().single()
        assertEquals(original, restored)
        assertTrue(restored.json().getJSONArray("items").getJSONObject(0).getJSONObject("facts").isNull("granted"))
        assertEquals("L7_APP", restored.json().getString("executorContext"))
    }

    @Test fun unfinishedRunIsRecoveredAsInterruptedWithoutRestartingIt() {
        val store = L7ProbeStore(temporary.root)
        store.save(report(L7ProbePhase.RUNNING))
        val restored = store.load().single()
        assertEquals(L7ProbePhase.INTERRUPTED, restored.phase)
        assertNotNull(restored.finished)
        assertEquals(L7ProbePhase.INTERRUPTED, store.load().single().phase)
    }

    @Test fun retentionRemovesOldReportsAndDeletionIsScopedToOneReport() {
        val store = L7ProbeStore(temporary.root)
        val reports = (1..12).map { report().also { value ->
            store.save(value)
            File(temporary.root, "${value.id}.json").setLastModified(1000L + it)
        } }
        val retained = store.load()
        assertEquals(10, retained.size)
        assertTrue(retained.any { it.id == reports.last().id })
        store.delete(reports.last().id)
        assertEquals(9, store.load().size)
        assertThrows(IllegalArgumentException::class.java) { store.delete("../../another-file") }
    }

    @Test fun clearReportsKeepsLogsAndUnrelatedFiles() {
        val store = L7ProbeStore(temporary.root)
        val saved = store.save(report())
        File(temporary.root, "${saved.id}.json.bak").writeText("old report")
        File(temporary.root, "${saved.id}.json.new").writeText("pending report")
        File(temporary.root, "diplay.log").writeText("runtime evidence")
        File(temporary.root, "unrelated.json").writeText("keep")
        store.clear()
        assertTrue(store.load().isEmpty())
        assertEquals(setOf("diplay.log", "unrelated.json"), temporary.root.list()!!.toSet())
        assertEquals("runtime evidence", File(temporary.root, "diplay.log").readText())
    }

    @Test fun utf8BudgetTruncatesItemsAndRecordsTheLoss() {
        val item = L7ProbeItem("large", "large", "ENVIRONMENT", L7ProbeOutcome.OBSERVED, "QUERY_ONLY",
            mapOf("synthetic" to "界".repeat(400_000)))
        val store = L7ProbeStore(temporary.root)
        val saved = store.save(report().copy(items = listOf(item, item, item)))
        assertTrue(saved.truncated)
        assertTrue(saved.items.size < 3)
        assertTrue(File(temporary.root, "${saved.id}.json").length() <= L7ProbeStore.MAX_BYTES)
    }

    @Test fun unsupportedSchemaAndNonAppContextCannotBecomeLocalVerifiedReports() {
        val source = report().json().put("executorContext", "ROOT")
        assertThrows(IllegalArgumentException::class.java) { L7ProbeReport.read(source) }
        val version = report().json().put("schemaVersion", 999)
        assertThrows(IllegalArgumentException::class.java) { L7ProbeReport.read(version) }
        File(temporary.root, "broken.json").writeText("broken")
        assertTrue(L7ProbeStore(temporary.root).load().isEmpty())
    }
}
