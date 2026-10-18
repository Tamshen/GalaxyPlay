package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class GalaxyOemSourceCatalogTest {
    private fun entry(name: String, sources: List<Int> = listOf(6), title: String = name) =
        GalaxyOemSourceCatalog.Entry(Any(), GalaxyOemSourceCatalog.Fields(name, title, "icon:$name", sources, 2, true, false))

    @Test fun addingOwnEntryRetainsForeignObjectsAndUsesLatestRead() {
        val original = entry("foreign")
        val concurrent = entry("concurrent", listOf(12))
        val own = entry("own", listOf(6, 13))
        var reads = 0
        var written = emptyList<Any>()
        val result = GalaxyOemSourceCatalog.ensure("own", 6, {
            reads++
            if (reads == 1) listOf(original) else if (reads == 2) listOf(original, concurrent)
            else listOf(original, concurrent, own)
        }, { own }, { written = it }, { true })
        assertEquals("ADDED", result)
        assertEquals(listOf(original.value, concurrent.value, own.value), written)
    }

    @Test fun refreshingOwnNameAndSourcesKeepsItsPositionAndAllForeignFields() {
        val foreign = entry("foreign")
        val prior = entry("own", listOf(13), "old")
        val updated = entry("own", listOf(13, 6), "GalaxyPlay")
        var current = listOf(prior, foreign)
        val result = GalaxyOemSourceCatalog.ensure("own", 6, { current }, { updated }, {
            assertEquals(listOf(updated.value, foreign.value), it)
            current = listOf(updated, foreign)
        }, { true })
        assertEquals("UPDATED", result)
    }

    @Test fun existingCompleteEntryDoesNotWriteOrDuplicateIt() {
        val own = entry("own")
        assertEquals("PRESENT", GalaxyOemSourceCatalog.ensure("own", 6, { listOf(own) },
            { own }, { error("已有字段一致不能写入") }, { true }))
    }

    @Test fun unavailableOrAmbiguousCatalogNeverOverwritesServiceConfiguration() {
        val own = entry("own")
        val cases = listOf(null, emptyList(), listOf(entry("")), listOf(entry("foreign"), entry("foreign")))
        for (values in cases) {
            val result = GalaxyOemSourceCatalog.ensure("own", 6, { values }, { own },
                { error("不可用目录不能写入") }, { true })
            assertTrue(result in listOf("CONFIG_UNAVAILABLE", "CONFIG_AMBIGUOUS"))
        }
    }

    @Test fun malformedOwnEntryCannotRegisterAnotherIdentityOrWrongSource() {
        for (wrong in listOf(entry("another"), entry("own", listOf(0)))) {
            assertEquals("INVALID_ENTRY", GalaxyOemSourceCatalog.ensure("own", 6, { listOf(entry("foreign")) },
                { wrong }, { error("条目未核对不能写入") }, { true }))
        }
    }

    @Test fun invalidationBeforeWriteRejectsLateRegistration() {
        var active = true
        val result = GalaxyOemSourceCatalog.ensure("own", 6, { listOf(entry("foreign")) }, {
            active = false
            entry("own")
        }, { error("结束后的注册不能写目录") }, { active })
        assertEquals("STALE", result)
    }

    @Test fun readbackRejectsMissingWrongIdentityFieldsAndForeignChanges() {
        val original = entry("foreign")
        val own = entry("own")
        val cases = listOf(
            null to "READBACK_MISSING",
            listOf(original) to "READBACK_MISMATCH",
            listOf(original, entry("own", title = "wrong")) to "READBACK_MISMATCH",
            listOf(entry("foreign", listOf(0)), own) to "READBACK_CHANGED",
            listOf(own) to "READBACK_CHANGED",
        )
        for ((after, expected) in cases) {
            var written = false
            assertEquals(expected, GalaxyOemSourceCatalog.ensure("own", 6,
                { if (written) after else listOf(original) }, { own }, { written = true }, { true }))
        }
    }

    @Test fun staleAfterBlockingWriteCannotClaimRegistrationSucceeded() {
        var active = true
        assertEquals("STALE", GalaxyOemSourceCatalog.ensure("own", 6, { listOf(entry("foreign")) },
            { entry("own") }, { active = false }, { active }))
    }

    @Test fun invalidationDuringReadbackCannotClaimTheOldSessionRegistered() {
        var active = true
        var written = false
        val foreign = entry("foreign")
        val own = entry("own")
        val result = GalaxyOemSourceCatalog.ensure("own", 6, {
            if (written) { active = false; listOf(foreign, own) } else listOf(foreign)
        }, { own }, { written = true }, { active })
        assertEquals("STALE", result)
    }
}
